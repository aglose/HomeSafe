#!/usr/bin/env python3
"""Numbers from a journey's traces (scripts/perf/record.sh), per step of the journey.

    trace_report.py steps   <prefix> [--top N] [--only a,b]   frames (P50/P90/P99/max) and main/RenderThread CPU per step;
                                                              --top lists each step's costliest main-thread sections
    trace_report.py cpu     <prefix> [--apps]                 core-seconds per thread group over the steps (--apps: only
                                                              the drawer apps' steps, with Home covered)
    trace_report.py drill   <prefix> <step> <slice> [thread]  the longest <slice> (SQL LIKE) in <step>: what ran under it,
                                                              and what its thread was doing (running, runnable, asleep)
    trace_report.py shaders <prefix>                          GPU program compiles on the app's RenderThread, per step
    trace_report.py inproc  <prefix>                          the in-process file: the app's sections and Metro's binding
                                                              spans by total time, and instants (clock ticks) per second per step

<prefix> is what record.sh was given: <prefix>.pftrace, and <prefix>.inproc.pftrace for `inproc`.
Uses the `perfetto` Python package (pip install perfetto), which fetches trace_processor itself.
Steps are the `HomeSafeJourney <name>:start|end` log marks the journey writes.
"""
import collections, sys
from perfetto.trace_processor import TraceProcessor

# Threads that exist for live video: the codec (in the media HAL process on an emulator), libwebrtc's,
# the SurfaceTexture → GL hand-off, ExoPlayer, and the app's EglRenderers.
VIDEO = ("(t.name like 'sh.h264%' or t.name like 'c2.%' or t.name like 'decoder-texture%' or t.name like 'MediaCodec%' "
         "or t.name like 'worker_thread%' or t.name like 'network_thread%' or t.name like 'signaling_thread%' "
         "or t.name like 'HomeSafeLive%' or t.name like 'ExoPlayer%')")
SYSTEM = ("system_server", "com.android.systemui", "com.google.android.apps.nexuslauncher")


def open_trace(path):
    tp = TraceProcessor(trace=path)
    return tp, (lambda sql: list(tp.query(sql)))


def steps_of(q):
    """(name, start, end) for each marked step, in order."""
    started, steps = {}, []
    for m in q("select ts, msg from android_logs where tag = 'HomeSafeJourney' order by ts"):
        name, _, kind = m.msg.rpartition(":")
        if kind == "start":
            started[name] = m.ts
        elif kind == "end" and name in started:
            steps.append((name, started.pop(name), m.ts))
    return steps


def app_process(q):
    """The app: the process that drew the most frames. (Started after the trace began, it may still be called zygote64.)"""
    names = ",".join(f"'{n}'" for n in SYSTEM)
    upid = q(f"""select f.upid from actual_frame_timeline_slice f join process p using(upid)
                 where coalesce(p.name, '') not like '%surfaceflinger%' and coalesce(p.name, '') not in ({names})
                 group by f.upid order by count(*) desc limit 1""")[0].upid
    main = q(f"select utid from thread where upid = {upid} and tid = (select pid from process where upid = {upid})")[0].utid
    render = q(f"select utid from thread where upid = {upid} and name = 'RenderThread' limit 1")[0].utid
    return upid, main, render


def cpu_ms(q, where, s, e):
    """Milliseconds of CPU the threads matching `where` (t: thread, s: sched) used between s and e."""
    return q(f"""select coalesce(sum(min(s.ts + s.dur, {e}) - max(s.ts, {s})), 0) as c
                 from sched s join thread t using(utid)
                 where s.ts < {e} and s.ts + s.dur > {s} and ({where})""")[0].c / 1e6


def pct(values, p):
    return sorted(values)[min(len(values) - 1, int(len(values) * p))] if values else 0


def cmd_steps(prefix, top=0, only=None):
    _, q = open_trace(prefix + ".pftrace")
    upid, main, render = app_process(q)
    print(f"{'step':24} {'frames':>6} {'p50':>6} {'p90':>6} {'p99':>7} {'max':>7} {'main ms':>8} {'RT ms':>7} {'s':>5}")
    for name, s, e in steps_of(q):
        if only and name not in only:
            continue
        durs = [r.dur / 1e6 for r in q(f"select dur from actual_frame_timeline_slice where upid = {upid} and ts >= {s} and ts < {e}")]
        print(f"{name:24} {len(durs):6d} {pct(durs, .5):6.1f} {pct(durs, .9):6.1f} {pct(durs, .99):7.1f} {max(durs or [0]):7.1f} "
              f"{cpu_ms(q, f't.utid = {main}', s, e):8.0f} {cpu_ms(q, f't.utid = {render}', s, e):7.0f} {(e - s) / 1e9:5.1f}")
        if top:
            for r in q(f"""select s.name, count(*) n, sum(s.dur) / 1e6 total, max(s.dur) / 1e6 longest
                           from slice s join thread_track tt on s.track_id = tt.id
                           where tt.utid = {main} and s.ts >= {s} and s.ts < {e} and s.depth > 0
                           group by s.name order by total desc limit {top}"""):
                print(f"      {r.total:9.1f}ms  n={r.n:<5d} longest={r.longest:8.1f}  {r.name[:100]}")


def cmd_cpu(prefix, apps_only=False):
    _, q = open_trace(prefix + ".pftrace")
    upid, main, render = app_process(q)
    steps = steps_of(q)
    if apps_only:
        steps = [x for x in steps if x[0].removeprefix("idle_").split("_")[0] in ("weather", "finance", "fitness", "atlas") and not x[0].endswith("_open")]
    wall = sum(e - s for _, s, e in steps) / 1e9
    groups = {
        "video (decode, WebRTC, EGL)": VIDEO,
        "app main thread": f"t.utid = {main}",
        "app RenderThread": f"t.utid = {render}",
        f"rest of the app": f"t.upid = {upid} and t.utid not in ({main}, {render}) and not {VIDEO}",
        "whole device": "s.utid != 0",
    }
    print(f"{len(steps)} steps, {wall:.0f} s")
    for label, where in groups.items():
        core_s = sum(cpu_ms(q, where, s, e) for _, s, e in steps) / 1e3
        print(f"  {label:30} {core_s:7.1f} core-s  ({core_s / wall:.2f} cores)")


def cmd_drill(prefix, step, like, thread=None):
    _, q = open_trace(prefix + ".pftrace")
    upid, _, _ = app_process(q)
    s, e = next((s, e) for n, s, e in steps_of(q) if n == step)
    on = f"and t.name = '{thread}'" if thread else ""
    top = q(f"""select s.id, s.ts, s.dur, s.name, t.name tn, t.utid from slice s join thread_track tt on s.track_id = tt.id
                join thread t using(utid) where t.upid = {upid} and s.name like '{like}' and s.ts >= {s} and s.ts < {e} {on}
                order by s.dur desc limit 1""")[0]
    print(f"{top.name} on {top.tn}: {top.dur / 1e6:.1f} ms, {(top.ts - s) / 1e6:.0f} ms into {step}")
    for r in q(f"""select name, depth, count(*) n, sum(dur) / 1e6 total, max(dur) / 1e6 longest from descendant_slice({top.id})
                   group by name, depth order by total desc limit 30"""):
        print(f"  depth {r.depth:<2} {r.total:8.1f}ms n={r.n:<5} longest={r.longest:7.1f}  {r.name[:100]}")
    states = q(f"""select state, sum(dur) / 1e6 d from thread_state where utid = {top.utid}
                   and ts >= {top.ts} and ts < {top.ts + top.dur} group by state order by d desc""")
    print("  thread was: " + ", ".join(f"{r.state} {r.d:.0f}ms" for r in states) + "   (S asleep/waiting, R runnable, Running on a CPU, D in the kernel)")


def cmd_shaders(prefix):
    _, q = open_trace(prefix + ".pftrace")
    upid, _, render = app_process(q)
    compiles = q(f"select s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id where s.name = 'shader_compile' and tt.utid = {render}")
    print(f"{len(compiles)} compiles, {sum(r.dur for r in compiles) / 1e6:.0f} ms on the RenderThread")
    for name, s, e in steps_of(q):
        mine = [r.dur for r in compiles if s <= r.ts < e]
        if mine:
            print(f"  {name:24} {len(mine):3d} compiles {sum(mine) / 1e6:7.0f} ms, longest {max(mine) / 1e6:5.0f} ms")


def cmd_inproc(prefix):
    sys_tp, q = open_trace(prefix + ".pftrace")
    steps = steps_of(q)
    sys_tp.close()
    _, iq = open_trace(prefix + ".inproc.pftrace")
    print("sections (dur > 0) by total time:")
    for r in iq("""select name, category, count(*) n, sum(dur) / 1e6 total, max(dur) / 1e6 longest from slice
                   where dur > 0 group by name order by total desc limit 25"""):
        print(f"  {r.total:8.1f}ms n={r.n:<5} longest={r.longest:7.1f}  [{r.category}] {r.name}")
    instants = [(r.ts, r.name) for r in iq("select ts, name from slice where dur = 0")]
    if instants:
        print("instants per second, per step:")
        for name, s, e in steps:
            counts = collections.Counter(n for t, n in instants if s <= t < e)
            if counts:
                print(f"  {name:24} " + ", ".join(f"{k} {v / ((e - s) / 1e9):.0f}/s" for k, v in counts.most_common()))


if __name__ == "__main__":
    a = sys.argv[1:]
    flag = lambda f: a[a.index(f) + 1] if f in a else None
    rest = [x for i, x in enumerate(a) if not x.startswith("--") and (i == 0 or a[i - 1] not in ("--top", "--only"))]
    cmd, args = rest[0], rest[1:]
    if cmd == "steps":
        cmd_steps(args[0], int(flag("--top") or 0), flag("--only").split(",") if flag("--only") else None)
    elif cmd == "cpu":
        cmd_cpu(args[0], "--apps" in a)
    elif cmd == "drill":
        cmd_drill(*args)
    elif cmd == "shaders":
        cmd_shaders(args[0])
    elif cmd == "inproc":
        cmd_inproc(args[0])
    else:
        sys.exit(__doc__)
