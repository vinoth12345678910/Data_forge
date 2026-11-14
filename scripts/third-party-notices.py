#!/usr/bin/env python3
"""Writes THIRD_PARTY_NOTICES.md: every runtime dependency of the Java modules (licence read from its POM in the Gradle
cache, through parent POMs) and of the web UI (licence read from node_modules). Usage: python3 scripts/third-party-notices.py"""
import glob, json, os, re, subprocess, sys, xml.etree.ElementTree as ET

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(root)
cache = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

out = subprocess.run(["./gradlew", "-q", ":cli:dependencies", ":server:dependencies", ":tui:dependencies",
                      "--configuration", "runtimeClasspath"], capture_output=True, text=True).stdout
deps = set()
for m in re.finditer(r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([0-9][^\s]*?)(?: -> ([^\s]+))?(?:\s|$)", out):
    g, a, v, r = m.groups()
    if g == "dev.forge" or g == "project":
        continue
    deps.add((g, a, r or v))

def pom_for(g, a, v):
    hits = glob.glob(f"{cache}/{g}/{a}/{v}/*/*.pom")
    return hits[0] if hits else None

def licences(g, a, v, depth=0):
    p = pom_for(g, a, v)
    if not p or depth > 4:
        return []
    try:
        t = ET.parse(p).getroot()
    except ET.ParseError:
        return []
    ns = {"m": t.tag.split("}")[0].strip("{")} if "}" in t.tag else {}
    q = (lambda x: f"m:{x}") if ns else (lambda x: x)
    names = [(l.findtext(q("name"), "", ns) or "").strip() for l in t.findall(f"{q('licenses')}/{q('license')}", ns)]
    names = [n for n in names if n]
    if names:
        return names
    par = t.find(q("parent"), ns)
    if par is not None:
        pg, pa, pv = (par.findtext(q(k), "", ns) for k in ("groupId", "artifactId", "version"))
        return licences(pg, pa, pv, depth + 1)
    return []

rows = []
for g, a, v in sorted(deps):
    lic = licences(g, a, v)
    rows.append((f"{g}:{a}", v, "; ".join(lic) if lic else "NOT FOUND in the POM (check by hand)"))

js = []
if os.path.exists("ui/package-lock.json"):
    lock = json.load(open("ui/package-lock.json"))
    for path, info in sorted(lock.get("packages", {}).items()):
        if not path or info.get("dev"):
            continue
        name = path.split("node_modules/")[-1]
        pj = f"ui/{path}/package.json"
        lic = "NOT FOUND (check by hand)"
        if os.path.exists(pj):
            l = json.load(open(pj)).get("license")
            lic = l if isinstance(l, str) else (l or {}).get("type", lic) if l else lic
        js.append((name, info.get("version", ""), lic))

with open("THIRD_PARTY_NOTICES.md", "w") as f:
    f.write("# Third-party notices\n\nData Forge is MIT licensed ([LICENSE](LICENSE)). It ships with, or is built from, the libraries below. "
            "Licences are read from each library's own metadata by `scripts/third-party-notices.py`; entries marked NOT FOUND "
            "need a manual check before a public release. Whether each licence is compatible with MIT distribution is for the owner to confirm: "
            "this file only lists what the metadata says.\n\n")
    f.write("## Java libraries (runtime)\n\n| Library | Version | Licence |\n|---|---|---|\n")
    for n, v, l in rows:
        f.write(f"| {n} | {v} | {l} |\n")
    f.write("\n## Web UI libraries (production dependencies, built into `ui/dist`)\n\n| Package | Version | Licence |\n|---|---|---|\n")
    for n, v, l in js:
        f.write(f"| {n} | {v} | {l} |\n")
    review = [(n, v, l) for n, v, l in rows + js if re.search(r"GPL|EPL|Eclipse|CDDL|MPL|NOT FOUND", l)]
    f.write("\n## Review before a public release\n\nThese entries are not plain MIT, BSD, ISC or Apache-2.0 licences. "
            "Some are dual licences where Apache-2.0 or the more permissive one can be chosen; the GNU entry carries an exception. "
            "This is a list for the owner to decide on, not legal advice.\n\n")
    for n, v, l in review:
        f.write(f"- {n} {v}: {l}\n")
    f.write("\n## Test data\n\nThe Pagila sample schema in `golden/holdout/pagila/` is used as a test target. "
            "Its licence is kept beside it in `golden/holdout/pagila/LICENSE.txt`; its source is in `SOURCE.md` there.\n")
print(f"{len(rows)} Java libraries, {len(js)} web packages; not found: "
      f"{sum('NOT FOUND' in r[2] for r in rows)} Java, {sum('NOT FOUND' in j[2] for j in js)} web")
