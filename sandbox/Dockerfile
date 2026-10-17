FROM python:3.12-alpine
RUN apk add --no-cache docker-cli
WORKDIR /app
COPY app.py .
EXPOSE 8090
CMD ["python3", "-u", "app.py"]
