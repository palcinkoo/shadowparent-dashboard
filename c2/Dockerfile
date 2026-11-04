FROM python:3.12-slim
WORKDIR /app
COPY c2/requirements.txt .
RUN pip install -r requirements.txt
COPY . .
ENV PORT=8080
EXPOSE 8080
CMD gunicorn --chdir c2 --bind 0.0.0.0:$PORT --workers 1 --timeout 120 app:app
