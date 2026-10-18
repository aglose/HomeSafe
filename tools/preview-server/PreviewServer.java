import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/*
 * Draws HomeSafe's @Preview functions through a Layoutlib that stays loaded, so a preview after an
 * edit costs a reload and a frame (about a second) rather than a JVM and a Layoutlib start (about
 * ten). Started and driven by scripts/preview; see docs/ui-previews.md.
 *
 * It runs Google's own renderer, the one Compose Preview Screenshot Testing uses (the
 * compose-preview-renderer jar), with the same inputs: the screenshot suite's engine reads them
 * from the file AGP writes for it (env com.android.junit.engine.input.parameters). What it does
 * differently is the class loading:
 *
 *  - One isolated class loader holds Layoutlib and the renderer for the life of the server.
 *    Layoutlib's native library can be loaded only once per JVM, which is why the screenshot
 *    suite, building a new loader on every run, needs a new JVM every time.
 *  - The app's own classes (every module's class jar and R classes) are "project" classes,
 *    loaded by a module class loader the renderer makes per bootstrap. When any of them changes
 *    on disk, the next request bootstraps again (about 0.2 s) and so sees the new code.
 *
 * The previews are drawn as themselves, private ones too, by their JVM name, with the @Preview
 * arguments from the catalogue :androidApp:generatePreviewTests writes (previews.tsv).
 *
 * HTTP on 127.0.0.1, port written to the file named by -Dpreview.portFile:
 *   /render?only=words&out=dir   draw the previews whose module, function or name contains a word
 *                                (only=* draws them all)
 *   /list                        the catalogue
 *   /stop                        exit
 * It exits by itself after -Dpreview.idleMinutes (default 30) without a request.
 */
public class PreviewServer {
    private record Preview(String id, String module, String className, String function, String name, Map<String, String> parameters) {
        boolean matches(List<String> words) {
            if (words.contains("*")) return true;
            for (String word : words) {
                String w = word.toLowerCase(Locale.ROOT);
                if (module.toLowerCase(Locale.ROOT).contains(w) || function.toLowerCase(Locale.ROOT).contains(w)
                    || name.toLowerCase(Locale.ROOT).contains(w)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Path root;
    private final Path catalogue;
    private final Object input;
    private final ClassLoader isolated;
    private final Constructor<?> bootstrapper;
    private final Constructor<?> screenshot;
    private final Class<?> previewScreenshot;
    private final List<String> classPath = new ArrayList<>();
    private final List<String> projectClassPath = new ArrayList<>();

    private Closeable renderer;
    private Method render;
    private String rendererStamp = "";
    private int generation;
    private long catalogueModified = -1;
    private List<Preview> previews = List.of();
    private volatile long lastRequest = System.currentTimeMillis();

    PreviewServer(Path root, Path catalogue) throws Exception {
        this.root = root;
        this.catalogue = catalogue;
        Class<?> inputClass = Class.forName("com.android.tools.screenshot.PreviewScreenshotTestEngineInput$RendererInput");
        input = inputClass.getField("INSTANCE").get(null);

        List<File> layoutlib = get("getLayoutlibClassPath");
        URL[] urls = new URL[layoutlib.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = layoutlib.get(i).toURI().toURL();
        // The engine's own loader: a URLClassLoader that also finds resources on the system loader.
        Constructor<?> loader = Class.forName("com.android.tools.screenshot.renderer.Renderer$ResourceEnhancedClassLoader")
            .getDeclaredConstructor(URL[].class, ClassLoader.class);
        loader.setAccessible(true);
        isolated = (ClassLoader) loader.newInstance(urls, ClassLoader.getPlatformClassLoader());

        bootstrapper = isolated.loadClass("com.android.tools.render.RenderEnvironmentBootstrapper").getConstructor(
            String.class, String.class, String.class, List.class, List.class, String.class, List.class, List.class);
        screenshot = isolated.loadClass("com.android.tools.render.compose.ComposeScreenshot")
            .getConstructor(String.class, List.class, Map.class, String.class, String.class);
        previewScreenshot = isolated.loadClass("com.android.tools.render.common.PreviewScreenshot");

        // Everything, as the screenshot suite passes it; then which of it is this repository's own
        // (the modules' class jars, R classes, androidApp's classes), reloaded on every change.
        Set<String> all = new LinkedHashSet<>();
        for (String getter : List.of("getMainAllClassPath", "getScreenshotAllClassPath")) {
            for (File f : this.<List<File>>get(getter)) all.add(f.getAbsolutePath());
        }
        Set<String> project = new LinkedHashSet<>();
        for (String getter : List.of("getMainProjectClassPath", "getScreenshotProjectClassPath")) {
            for (File f : this.<List<File>>get(getter)) project.add(f.getAbsolutePath());
        }
        for (String entry : all) {
            if (entry.startsWith(root.toString() + File.separator) && !entry.contains(File.separator + ".gradle" + File.separator)) {
                project.add(entry);
            }
        }
        classPath.addAll(all);
        projectClassPath.addAll(project);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String getter) throws Exception {
        return (T) input.getClass().getMethod(getter).invoke(input);
    }

    private static List<String> paths(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getAbsolutePath());
        return out;
    }

    /** Changes whenever a project class jar or directory, or the resources Layoutlib reads, changes. */
    private String stamp() throws Exception {
        StringBuilder stamp = new StringBuilder();
        List<String> watched = new ArrayList<>(projectClassPath);
        watched.add(((File) get("getResourceApkPath")).getAbsolutePath());
        for (String entry : watched) {
            Path path = Path.of(entry);
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    long newest = files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().lastModified()).max().orElse(0);
                    stamp.append(entry).append('@').append(newest).append(';');
                }
            } else {
                File file = path.toFile();
                stamp.append(entry).append('@').append(file.lastModified()).append(':').append(file.length()).append(';');
            }
        }
        return stamp.toString();
    }

    private void ensureRenderer(StringBuilder log) throws Exception {
        String now = stamp();
        if (renderer != null && now.equals(rendererStamp)) return;
        // A stamp taken mid-build would load half-written jars; scripts/preview only asks once Gradle is done.
        long t0 = System.nanoTime();
        if (renderer != null) renderer.close();
        Object boot = bootstrapper.newInstance(
            ((File) get("getFontsPath")).getAbsolutePath(),
            ((File) get("getResourceApkPath")).getAbsolutePath(),
            (String) get("getNamespace"),
            classPath, projectClassPath,
            ((File) get("getLayoutlibDataDir")).getAbsolutePath(),
            paths(get("getTestRuntimeResourceDirs")),
            paths(get("getTestRuntimeRClassJars")));
        renderer = (Closeable) boot.getClass().getMethod("bootstrap").invoke(boot);
        render = renderer.getClass().getMethod("render", previewScreenshot, String.class);
        rendererStamp = now;
        generation++;
        log.append(String.format("loaded the app's classes (generation %d) in %d ms%n", generation, millis(t0)));
    }

    private List<Preview> catalogue() throws IOException {
        long modified = Files.getLastModifiedTime(catalogue).toMillis();
        if (modified == catalogueModified) return previews;
        List<Preview> read = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String line : Files.readAllLines(catalogue)) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            Map<String, String> parameters = new LinkedHashMap<>();
            if (f.length > 4 && !f[4].isEmpty()) {
                for (String pair : f[4].split("\u001F")) {
                    int eq = pair.indexOf('=');
                    parameters.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            String base = f[0] + "_" + f[2];
            int n = seen.merge(base, 1, Integer::sum);
            read.add(new Preview(n == 1 ? base : base + "_" + n, f[0], f[1], f[2], f[3], parameters));
        }
        previews = read;
        catalogueModified = modified;
        return previews;
    }

    private synchronized String render(List<String> words, Path out) throws Exception {
        StringBuilder log = new StringBuilder();
        List<Preview> wanted = catalogue().stream().filter(p -> p.matches(words)).toList();
        if (wanted.isEmpty()) {
            return "error: no @Preview matches " + words + " (scripts/preview --list shows them all)\n";
        }
        ensureRenderer(log);
        Path work = Files.createTempDirectory("preview-server");
        Files.createDirectories(out);
        for (Preview preview : wanted) {
            long t0 = System.nanoTime();
            String methodFqn = preview.className() + "." + preview.function();
            Object shot = screenshot.newInstance(methodFqn, List.of(), preview.parameters(), preview.id(), null);
            List<?> results;
            try {
                results = (List<?>) render.invoke(renderer, shot, work.toString());
            } catch (InvocationTargetException e) {
                log.append("error ").append(preview.id()).append(": ").append(e.getCause()).append('\n');
                continue;
            }
            for (Object result : results) {
                String image = (String) result.getClass().getMethod("getImagePath").invoke(result);
                Object error = result.getClass().getMethod("getError").invoke(result);
                if (image != null && Files.exists(work.resolve(image))) {
                    Path target = out.resolve(preview.id() + ".png");
                    Files.move(work.resolve(image), target, StandardCopyOption.REPLACE_EXISTING);
                    log.append(String.format("%5d ms  %s%n", millis(t0), target));
                }
                if (error != null) log.append("error ").append(preview.id()).append(": ").append(describe(error)).append('\n');
            }
        }
        deleteTree(work);
        return log.toString();
    }

    /** A ScreenshotError as text: its message, the render problems, and missing or broken classes. */
    private static String describe(Object error) throws Exception {
        StringBuilder text = new StringBuilder(String.valueOf(error.getClass().getMethod("getMessage").invoke(error)));
        Set<String> shown = new LinkedHashSet<>();
        for (Object problem : (List<?>) error.getClass().getMethod("getProblems").invoke(error)) {
            String html = String.valueOf(problem.getClass().getMethod("getHtml").invoke(problem));
            Object stack = problem.getClass().getMethod("getStackTrace").invoke(problem);
            String entry = "\n  " + html.replaceAll("<[^>]+>", "").replace("(Details)", "").replaceAll("\\s+", " ").trim()
                + (stack == null ? "" : "\n    " + firstLines(stack.toString(), 6).replace("\n", "\n    "));
            if (shown.add(entry)) text.append(entry);
        }
        for (String getter : List.of("getMissingClasses", "getBrokenClasses")) {
            List<?> classes = (List<?>) error.getClass().getMethod(getter).invoke(error);
            if (!classes.isEmpty()) text.append("\n  ").append(getter.substring(3)).append(": ").append(classes);
        }
        return text.toString();
    }

    private static String firstLines(String text, int n) {
        String[] lines = text.split("\n");
        return String.join("\n", List.of(lines).subList(0, Math.min(n, lines.length)));
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
        }
    }

    private static long millis(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> query = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) return query;
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("preview.root")).toAbsolutePath();
        Path catalogue = Path.of(System.getProperty("preview.catalogue"));
        Path portFile = Path.of(System.getProperty("preview.portFile"));
        long idleMillis = Long.parseLong(System.getProperty("preview.idleMinutes", "30")) * 60_000;

        long t0 = System.nanoTime();
        PreviewServer server = new PreviewServer(root, catalogue);
        StringBuilder log = new StringBuilder();
        server.ensureRenderer(log);
        System.out.print(log);
        System.out.printf("ready in %d ms%n", millis(t0));

        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/render", exchange -> {
            server.lastRequest = System.currentTimeMillis();
            Map<String, String> q = query(exchange.getRequestURI());
            try {
                List<String> words = List.of(q.getOrDefault("only", "").split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
                Path out = Path.of(q.getOrDefault("out", root.resolve("build/ui-previews/live").toString()));
                String body = server.render(words, out);
                System.out.print(body);
                // A preview that couldn't be drawn makes scripts/preview exit non-zero.
                reply(exchange, body.startsWith("error") || body.contains("\nerror ") ? 422 : 200, body);
            } catch (Throwable e) {
                e.printStackTrace();
                reply(exchange, 500, "error: " + e + "\n");
            }
        });
        http.createContext("/list", exchange -> {
            server.lastRequest = System.currentTimeMillis();
            StringBuilder body = new StringBuilder();
            synchronized (server) {
                for (Preview p : server.catalogue()) body.append(p.id()).append('\t').append(p.name()).append('\n');
            }
            reply(exchange, 200, body.toString());
        });
        http.createContext("/stop", exchange -> {
            reply(exchange, 200, "stopping\n");
            System.exit(0);
        });
        http.start();
        Files.writeString(portFile, String.valueOf(http.getAddress().getPort()));

        while (true) {
            Thread.sleep(30_000);
            if (System.currentTimeMillis() - server.lastRequest > idleMillis) {
                System.out.println("idle, exiting");
                Files.deleteIfExists(portFile);
                System.exit(0);
            }
        }
    }
}
