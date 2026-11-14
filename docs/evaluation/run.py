"""Run LabDesk retrieval and answer checks against an evaluation-only knowledge base."""

import argparse
import getpass
import json
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
CASES = Path(__file__).with_name("questions.json")


def request(base, path, token=None, body=None):
    headers = {"Authorization": token} if token else {}
    if body is not None:
        body = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    return urlopen(Request(base + path, data=body, headers=headers), timeout=180)


def result(base, path, token=None, body=None):
    with request(base, path, token, body) as response:
        data = json.load(response)
    if str(data.get("code")) != "0":
        raise RuntimeError(f"LabDesk API {path.split('?')[0]} returned {data.get('code')}: {data.get('message', '')}")
    return data["data"]


def read_answer(base, query, token):
    deltas, sources = [], []
    event, completed = "", False
    with request(base, "/rag/v3/chat?" + query, token) as response:
        if response.headers.get_content_type() != "text/event-stream":
            raise RuntimeError("The chat endpoint did not return SSE")
        for raw in response:
            line = raw.decode("utf-8").rstrip("\r\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:") and event in {"message", "finish", "done", "error", "reject"}:
                if event in {"error", "reject"}:
                    raise RuntimeError(f"Chat ended with an SSE {event} event")
                if event == "done":
                    completed = True
                elif event == "message":
                    payload = json.loads(line[5:])
                    if payload.get("type") != "think":
                        deltas.append(payload.get("delta", ""))
                elif event == "finish":
                    sources = json.loads(line[5:]).get("sources") or []
    if not completed or not deltas:
        raise RuntimeError("The stream did not finish with answer text")
    return "".join(deltas), sources


def check_case(base, case, token):
    query = urlencode({"question": case["question"]})
    retrieval = result(base, "/rag/eval?" + query, token)
    started = time.monotonic()
    answer, sources = read_answer(base, query, token)
    answer_ms = round((time.monotonic() - started) * 1000)
    names = [Path(source["docName"]).stem for source in sources if source.get("docName")]
    return {
        **case,
        "retrieval": {field: retrieval.get(field) for field in
                      ("retrievedDocIds", "retrievedChunkIds", "retrievedContexts",
                       "retrievedContextDocIds", "latencyMs")},
        "answer": answer,
        "citations": sources,
        "answerMs": answer_ms,
        "missingAnswerTokens": [marker for marker in case["answerTokens"] if marker not in answer],
        "missingCitations": [name for name in case["sources"] if name not in names],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:9090/api/labdesk")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--output", type=Path, default=ROOT / "local/logs/labdesk-evaluation.jsonl")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    password = getpass.getpass("LabDesk administrator password: ")
    login = result(base, "/auth/login", body={"username": args.user, "password": password})
    del password
    if login.get("role") != "admin":
        raise RuntimeError("An administrator account is required for the retrieval evaluation endpoint")
    token = login["token"]
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    failed = []
    try:
        with args.output.open("w", encoding="utf-8") as output:
            for case in cases:
                try:
                    item = check_case(base, case, token)
                except (OSError, ValueError, RuntimeError) as error:
                    item = {**case, "error": str(error)}
                    failed.append(case["id"])
                output.write(json.dumps(item, ensure_ascii=False) + "\n")
                output.flush()
                print(f"{case['id']}: {'captured' if 'error' not in item else 'error'}")
    finally:
        result(base, "/auth/logout", token=token, body={})
    if failed:
        raise SystemExit("Cases with execution errors: " + ", ".join(failed))


if __name__ == "__main__":
    main()
