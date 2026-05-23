#!/usr/bin/env python3
"""Summarize a pprof CPU profile without needing `go tool pprof`.

The Pyroscope-exported `cpu.pprof` files are gzipped protobuf in the
standard pprof schema. This stdlib-only reader aggregates CPU time by
function (both self/leaf and cumulative) and prints a top-N flat report,
filterable to the application's own frames.

Usage:
    python3 loadtest/analyze_pprof.py <cpu.pprof> [--top N] [--filter SUBSTR]
"""
import gzip
import sys


def _read_varint(buf, i):
    shift = 0
    result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, i
        shift += 7


def _fields(buf):
    """Yield (field_number, wire_type, value) for a protobuf message.

    value is an int for varint/fixed, or a memoryview slice for length-delimited.
    """
    i, n = 0, len(buf)
    while i < n:
        tag, i = _read_varint(buf, i)
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            val, i = _read_varint(buf, i)
            yield field, wire, val
        elif wire == 2:
            length, i = _read_varint(buf, i)
            yield field, wire, buf[i:i + length]
            i += length
        elif wire == 1:
            yield field, wire, buf[i:i + 8]; i += 8
        elif wire == 5:
            yield field, wire, buf[i:i + 4]; i += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")


def _packed_varints(buf):
    out, i, n = [], 0, len(buf)
    while i < n:
        v, i = _read_varint(buf, i)
        out.append(v)
    return out


def parse(path):
    with gzip.open(path, "rb") as fh:
        data = fh.read()

    string_table = []
    sample_types = []   # list of (type_str_idx, unit_str_idx)
    samples = []        # list of (location_ids[], values[])
    functions = {}      # id -> name_str_idx
    locations = {}      # id -> [function_id, ...] (top line first)

    for field, wire, val in _fields(memoryview(data)):
        if field == 6 and wire == 2:                      # string_table
            string_table.append(bytes(val).decode("utf-8", "replace"))
        elif field == 1 and wire == 2:                    # sample_type (ValueType)
            t = u = 0
            for f2, _w, v2 in _fields(val):
                if f2 == 1:
                    t = v2
                elif f2 == 2:
                    u = v2
            sample_types.append((t, u))
        elif field == 2 and wire == 2:                    # sample
            loc_ids, values = [], []
            for f2, w2, v2 in _fields(val):
                if f2 == 1:
                    loc_ids = _packed_varints(v2) if w2 == 2 else [v2]
                elif f2 == 2:
                    values = _packed_varints(v2) if w2 == 2 else [v2]
            samples.append((loc_ids, values))
        elif field == 4 and wire == 2:                    # location
            loc_id, fn_ids = 0, []
            for f2, _w, v2 in _fields(val):
                if f2 == 1:
                    loc_id = v2
                elif f2 == 4:                             # line (repeated)
                    for f3, _w3, v3 in _fields(v2):
                        if f3 == 1:
                            fn_ids.append(v3)
            locations[loc_id] = fn_ids
        elif field == 5 and wire == 2:                    # function
            fn_id, name = 0, 0
            for f2, _w, v2 in _fields(val):
                if f2 == 1:
                    fn_id = v2
                elif f2 == 2:
                    name = v2
            functions[fn_id] = name

    def fname(fn_id):
        return string_table[functions.get(fn_id, 0)] if fn_id in functions else "?"

    # Pick the CPU value column: prefer sample_type "cpu", else "samples", else last.
    cpu_idx = len(sample_types) - 1
    for idx, (t, _u) in enumerate(sample_types):
        if string_table[t] == "cpu":
            cpu_idx = idx
            break
    else:
        for idx, (t, _u) in enumerate(sample_types):
            if string_table[t] == "samples":
                cpu_idx = idx
                break
    unit = string_table[sample_types[cpu_idx][1]] if sample_types else "?"

    self_cpu, cum_cpu, total = {}, {}, 0
    for loc_ids, values in samples:
        if cpu_idx >= len(values):
            continue
        v = values[cpu_idx]
        total += v
        # leaf = first location's top line function (pprof orders leaf-first)
        stack_fns = []
        for lid in loc_ids:
            stack_fns.extend(locations.get(lid, []))
        if stack_fns:
            leaf = fname(stack_fns[0])
            self_cpu[leaf] = self_cpu.get(leaf, 0) + v
        for fn in {fname(f) for f in stack_fns}:
            cum_cpu[fn] = cum_cpu.get(fn, 0) + v
    return self_cpu, cum_cpu, total, unit


def _fmt(ns, unit):
    if unit == "nanoseconds":
        return f"{ns / 1e9:.2f}s"
    return str(ns)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    top = 25
    filt = None
    for i, a in enumerate(sys.argv):
        if a == "--top":
            top = int(sys.argv[i + 1])
        elif a == "--filter":
            filt = sys.argv[i + 1]
    if not args:
        print(__doc__)
        sys.exit(1)

    self_cpu, cum_cpu, total, unit = parse(args[0])
    print(f"# pprof CPU summary: {args[0]}")
    print(f"# total CPU samples: {_fmt(total, unit)} ({unit}); filter={filt or 'none'}\n")

    def report(title, table):
        rows = sorted(table.items(), key=lambda kv: kv[1], reverse=True)
        if filt:
            rows = [r for r in rows if filt in r[0]]
        print(f"## {title} (top {top})")
        print(f"{'CPU':>10}  {'%':>6}  function")
        for name, v in rows[:top]:
            pct = 100.0 * v / total if total else 0
            print(f"{_fmt(v, unit):>10}  {pct:6.2f}  {name}")
        print()

    report("Self (leaf) CPU — where time is actually spent", self_cpu)
    report("Cumulative CPU — time spent in or below", cum_cpu)


if __name__ == "__main__":
    main()
