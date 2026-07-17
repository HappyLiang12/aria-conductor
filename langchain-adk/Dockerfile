FROM python:3.11-slim

RUN groupadd -r adk && useradd -r -g adk -s /bin/false adk
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

RUN chown -R adk:adk /app
USER adk

EXPOSE 9300

ENV HOST=0.0.0.0
ENV PORT=9300

HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=15s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:9300/health')" || exit 1

CMD ["python", "-m", "uvicorn", "src.server:app", "--host", "0.0.0.0", "--port", "9300"]
