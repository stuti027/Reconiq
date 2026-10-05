"""One-off check that the Gemini API key and model name work.

Run from ai-service/:  python -m app.check_gemini
"""
import os
from pathlib import Path

from dotenv import load_dotenv
from google import genai

load_dotenv(Path(__file__).resolve().parent.parent / ".env")


def main() -> None:
    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])
    model = os.environ["GEMINI_MODEL"]
    response = client.models.generate_content(
        model=model,
        contents="Reply with exactly one word: pong",
    )
    print(f"model={model} reply={response.text.strip()!r}")


if __name__ == "__main__":
    main()

