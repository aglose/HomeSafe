package com.meticulouscreations.homesafe.fakefrigate

/**
 * Runs the fake Frigate on its own, for driving the real app by hand or from an Android CLI
 * journey on an emulator (which reaches the host machine at 10.0.2.2):
 *
 *     ./gradlew :fake-frigate:run --args="--port 8971 --host 0.0.0.0 --advertise 10.0.2.2"
 *
 * Sign in with `admin` / `correct-horse` (or the read-only `viewer` / `just-looking`). Add
 * `--quiet` for a server with cameras but no history.
 */
fun main(args: Array<String>) {
    fun option(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val port = option("port")?.toInt() ?: 8971
    val host = option("host") ?: "0.0.0.0"
    val advertise = option("advertise") ?: "127.0.0.1"
    val state = if ("--quiet" in args) FakeFrigateState.quiet() else FakeFrigateState.household()
    val server = FakeFrigateServer(state).start(port = port, host = host, advertiseHost = advertise)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Fake Frigate listening on $host:${server.port} — sign in at ${server.baseUrl} as ${FakeFrigateState.ADMIN.username} / ${FakeFrigateState.ADMIN.password}")
    Thread.currentThread().join()
}
