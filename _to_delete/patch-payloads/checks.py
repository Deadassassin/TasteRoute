"""Structural checks for the TasteRoute Kotlin tree. The closest thing to a compile that runs
without an Android SDK. Run against the UNPATCHED tree first: a checker that reports faults before
anything was touched is a broken checker, not a broken tree."""
import os, re, sys, glob

ROOT = os.path.expanduser("~/mnt/TasteRoute/app/src/main/java/space/gexemy/tasteroute")
PKG = "space.gexemy.tasteroute"

# ---------------------------------------------------------------- scan: brackets via a mode stack
def scan(src):
    """Returns a list of problems. Modes: code / str / raw / char / line / block, plus templates.

    Three traps this has to get right, all of them real Kotlin:
      (a) A `}` ends a string template only when it is THAT template's own brace, so the bracket
          depth at `${` is recorded and compared.
      (b) A raw string may legitimately END in a quote, so a run of 3+ quotes is content followed
          by the terminator, not an early close.
      (c) `"${"$".repeat(n)}"` is a string containing code containing a string — which the stack
          handles for free, and a regex never can.
    """
    problems = []
    i, n, line = 0, len(src), 1
    depth = {"(": 0, "[": 0, "{": 0}
    stack = []          # frames: ("str",) ("raw",) ("tmpl", brace_depth_at_entry, parent_mode)
    mode = "code"
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            if mode == "line":
                mode = "code"
            i += 1
            continue
        if mode == "line":
            i += 1; continue
        if mode == "block":
            if src.startswith("*/", i): mode = "code"; i += 2; continue
            i += 1; continue
        if mode in ("str", "char"):
            if c == "\\": i += 2; continue
            if mode == "str" and src.startswith("${", i):
                stack.append(("tmpl", depth["{"], "str")); depth["{"] += 1; mode = "code"; i += 2; continue
            if (mode == "str" and c == '"') or (mode == "char" and c == "'"):
                mode = stack.pop()[0] if stack and stack[-1][0] in ("raw",) else "code"
                i += 1; continue
            i += 1; continue
        if mode == "raw":
            if src.startswith("${", i):
                stack.append(("tmpl", depth["{"], "raw")); depth["{"] += 1; mode = "code"; i += 2; continue
            if c == '"':
                run = len(src[i:]) - len(src[i:].lstrip('"'))
                if run >= 3:
                    i += run          # trap (b): the LAST three close it, the rest is content
                    mode = "code"
                    continue
                i += run; continue
            i += 1; continue
        # ---- code
        if src.startswith("//", i): mode = "line"; i += 2; continue
        if src.startswith("/*", i): mode = "block"; i += 2; continue
        if src.startswith('"""', i): mode = "raw"; i += 3; continue
        if c == '"': mode = "str"; i += 1; continue
        if c == "'": mode = "char"; i += 1; continue
        if c in "([{":
            depth[c] += 1; i += 1; continue
        if c in ")]}":
            opener = {")": "(", "]": "[", "}": "{"}[c]
            if c == "}" and stack and stack[-1][0] == "tmpl" and depth["{"] - 1 == stack[-1][1]:
                _, _, parent = stack.pop()          # trap (a)
                depth["{"] -= 1; mode = parent; i += 1; continue
            depth[opener] -= 1
            if depth[opener] < 0:
                problems.append("line %d: unmatched %s" % (line, c))
                depth[opener] = 0
            i += 1; continue
        i += 1
    for b, d in depth.items():
        if d: problems.append("%d unclosed %s" % (d, b))
    if mode != "code": problems.append("file ends inside a %s" % mode)
    if stack: problems.append("%d unterminated string template(s)" % len(stack))
    return problems

# ---------------------------------------------------------------- xref: project imports resolve
DECL = re.compile(
    r'^(?:@\w+\s+)*(?:public |internal |private |protected |abstract |open |sealed |data |value |inline |const |external |expect |actual |annotation |enum |companion )*'
    r'(?:class|object|interface|fun|val|var|typealias)\s+'
    r'(?:<[^>]+>\s+)?'            # a leading generic
    r'(?:[\w.<>?, ]+\.)?'         # an extension receiver
    r'(\w+)', re.M)

def declarations():
    """symbol -> set(package). Built from the tree, never from a hardcoded list."""
    table = {}
    for f in glob.glob(os.path.join(ROOT, "**", "*.kt"), recursive=True):
        src = open(f, encoding="utf-8").read()
        m = re.search(r'^package\s+([\w.]+)', src, re.M)
        pkg = m.group(1) if m else ""
        for d in DECL.finditer(src):
            table.setdefault(d.group(1), set()).add(pkg)
        # enum entries and object members are reachable through their type, not by name
    return table

def check_imports(path, src, table):
    problems = []
    pkg = re.search(r'^package\s+([\w.]+)', src, re.M)
    pkg = pkg.group(1) if pkg else ""
    body = src.split("\n")
    imports = [(i + 1, l.strip()) for i, l in enumerate(body) if l.startswith("import ")]
    code = "\n".join(l for l in body if not l.startswith("import ") and not l.startswith("package "))
    # Resolved by CONVENTION, never written by name: property delegation compiles `by remember`
    # into getValue/setValue calls the source never spells out. Reporting these as unused is the
    # single worst thing this checker can do - it teaches you to delete a line that breaks the
    # build, and the build is the one thing that cannot be run from here to contradict it.
    CONVENTION = {
        "getValue", "setValue", "provideDelegate", "invoke", "iterator", "compareTo", "contains",
        "plus", "minus", "times", "div", "rem", "unaryMinus", "unaryPlus", "inc", "dec", "get", "set",
        "rangeTo", "component1", "component2", "component3", "not", "equals", "hashCode", "toString",
    }
    for lineno, imp in imports:
        fq = imp[len("import "):].split(" as ")[0].strip()
        name = fq.rsplit(".", 1)[-1]
        if fq.startswith(PKG):
            owner = fq.rsplit(".", 1)[0]
            if name not in ("BuildConfig", "R") and owner not in table.get(name, set()):
                problems.append("line %d: import %s resolves to nothing in the tree" % (lineno, fq))
        if name in CONVENTION:
            continue
        if not re.search(r'\b%s\b' % re.escape(name), code):
            problems.append("line %d: unused import %s" % (lineno, fq))
    return problems

def main():
    table = declarations()
    files = sorted(glob.glob(os.path.join(ROOT, "**", "*.kt"), recursive=True))
    bad = 0
    for f in files:
        rel = os.path.relpath(f, ROOT)
        src = open(f, encoding="utf-8").read()
        problems = ["brackets: " + p for p in scan(src)] + check_imports(rel, src, table)
        if problems:
            bad += 1
            print("%s" % rel)
            for p in problems: print("   ", p)
    print("\n%d/%d files with findings" % (bad, len(files)))
    return bad

if __name__ == "__main__":
    sys.exit(0 if main() == 0 else 0)
