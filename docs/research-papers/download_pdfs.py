#!/usr/bin/env python3
"""Download available CISpaces research PDFs into this directory.

Run from the repository root:
    python3 docs/research-papers/download_pdfs.py

Requires Python 3 standard library only. Downloads are verified to begin with
the PDF signature. HTTP failures are reported, not hidden. Review publisher
licensing before redistributing the resulting files.
"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

DEST = Path(__file__).resolve().parent
SOURCES = {
    "2018-fact-extraction-to-report-generation.pdf": [
        "https://www.southampton.ac.uk/~sem03/comma-2018-final.pdf",
        "https://eprints.soton.ac.uk/425175/1/aFAIA305_0269_1_.pdf",
    ],
    "2023-human-machine-collaboration-expert-evaluation.pdf": [
        "https://iris.unibs.it/retrieve/93e0e174-04c3-4168-ab30-56e6c7bf12ea/Human_Machine_Collaboration_in_Intelligence_Analysis.pdf",
    ],
}

def main():
    count = 0
    for filename, urls in SOURCES.items():
        target = DEST / filename
        if target.exists() and target.read_bytes().startswith(b"%PDF-"):
            print(f"OK already downloaded: {filename}")
            count += 1
            continue
        for url in urls:
            try:
                request = Request(url, headers={"User-Agent": "Mozilla/5.0 (CISpaces educational research archival)"})
                with urlopen(request, timeout=30) as resp:
                    body = resp.read()
                if not body.startswith(b"%PDF-"):
                    raise ValueError("response is not a PDF")
                target.write_bytes(body)
                print(f"Downloaded: {filename} ({len(body):,} bytes)")
                count += 1
                break
            except (HTTPError, URLError, TimeoutError, ValueError) as exc:
                print(f"Unavailable: {url}: {exc}")
        else:
            print(f"FAILED: {filename} — no valid PDF retrieved")
    print(f"Completed: {count}/{len(SOURCES)} PDFs present")
    if count != len(SOURCES):
        raise SystemExit(1)

if __name__ == "__main__":
    main()
