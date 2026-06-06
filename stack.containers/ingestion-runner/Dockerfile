FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates file \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY stack.containers/ingestion-runner/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY stack.containers/ingestion-runner/ingestion_runner.py /app/ingestion_runner.py

CMD ["python", "-m", "uvicorn", "ingestion_runner:app", "--host", "0.0.0.0", "--port", "8090"]
