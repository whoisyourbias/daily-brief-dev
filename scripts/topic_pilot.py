"""Collect one 08:00 KST issue. Output is a private pilot artifact for editorial review."""
import concurrent.futures
import datetime as dt
import html
import json
import os
import pathlib
import re
import urllib.request
import xml.etree.ElementTree as ET

KST = dt.timezone(dt.timedelta(hours=9))
HN = "https://hacker-news.firebaseio.com/v0/"
TOPICS = {
    "AI·개발 도구": r"\b(ai|llm|agent[s]?|compiler|ide|codex|claude)\b|에이전트|개발 도구",
    "백엔드·인프라": r"postgres|database|kubernetes|docker|server|backend|서버|데이터베이스|인프라",
    "프론트엔드": r"react|javascript|typescript|browser|frontend|front-end|\bcss\b|브라우저|프론트엔드",
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=12) as response:
        return response.read()


def stories(cutoff):
    start = cutoff - dt.timedelta(days=1)
    ids = json.loads(fetch(HN + "topstories.json"))

    def hn_item(identifier):
        item = json.loads(fetch(f"{HN}item/{identifier}.json")) or {}
        if item.get("type") == "story" and not (item.get("dead") or item.get("deleted")):
            return ("HN", item.get("title", ""), item.get("url", ""), item.get("url", ""),
                    dt.datetime.fromtimestamp(item["time"], KST))

    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        rows = [row for row in pool.map(hn_item, ids) if row]
    ns = {"a": "http://www.w3.org/2005/Atom"}
    feed = ET.fromstring(fetch("https://news.hada.io/rss/news"))
    geek = []
    for entry in feed.findall("a:entry", ns):
        links = [link.get("href", "") for link in entry.findall("a:link", ns)
                 if link.get("rel", "alternate") == "alternate"]
        url = next((link for link in links if link.startswith("https://news.hada.io/")), "")
        identity = next((link for link in links if link != url), url)
        published = dt.datetime.fromisoformat(entry.findtext("a:published", namespaces=ns).replace("Z", "+00:00"))
        geek.append(("GeekNews", entry.findtext("a:title", "", ns), url, identity, published))
    rows.extend(sorted(geek, key=lambda row: row[-1], reverse=True))
    return [row for row in rows if start <= row[-1] < cutoff and row[1] and row[2].startswith(("https://", "http://"))]


def select(rows, topic=None):
    chosen, seen, counts = [], set(), {}
    for row in rows:
        source = row["source"]
        if topic and topic not in row["topics"]:
            continue
        if counts.get(source, 0) < 4 and row["identity"] not in seen:
            chosen.append(row)
            counts[source] = counts.get(source, 0) + 1
            seen.add(row["identity"])
    return chosen


def render(rows):
    sections = []
    for topic in [None, *TOPICS]:
        links = "".join(f'<li><a href="{html.escape(r["url"], quote=True)}">{html.escape(r["title"])}</a> · {r["source"]}</li>'
                        for r in select(rows, topic))
        sections.append(f'<h2>{topic or "공통판"}</h2>' + (f'<ul>{links}</ul>' if links else '<p>해당 기사 없음</p>'))
    return '<!doctype html><meta charset="utf-8"><title>Daily Brief 시범판</title>' + "".join(sections)


if __name__ == "__main__":
    now = dt.datetime.now(KST)
    cutoff = now.replace(hour=8, minute=0, second=0, microsecond=0)
    if now < cutoff:
        cutoff -= dt.timedelta(days=1)
    rows = [dict(source=s, title=t, url=u, identity=i, published=p.isoformat(),
                 topics=[name for name, pattern in TOPICS.items() if re.search(pattern, t, re.I)])
            for s, t, u, i, p in stories(cutoff)]
    out = pathlib.Path(os.environ.get("PILOT_OUTPUT", "pilot-output")) / str(cutoff.date())
    out.mkdir(parents=True, exist_ok=True)
    (out / "candidates.json").write_text(json.dumps(dict(observed=now.isoformat(), cutoff=cutoff.isoformat(), articles=rows), ensure_ascii=False, indent=2))
    (out / "samples.html").write_text(render(rows))
