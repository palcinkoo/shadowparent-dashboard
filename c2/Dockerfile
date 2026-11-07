FROM python:3.12-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY c2/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY c2/ ./c2/

ENV PORT=8080
EXPOSE 8080

# Run with gunicorn using gthread workers for better concurrency
CMD ["gunicorn", "--chdir", "c2", "--bind", "0.0.0.0:8080", \
     "--workers", "2", "--threads", "4", \
     "--worker-class", "gthread", \
     "--timeout", "120", \
     "app:app"]
