FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv
COPY requirements.txt .
# fbchat-v2 2.3.4 is on PyPI (checked 2026-11-09). If PyPI ever fails, install from the git tag instead:
#   pip install "git+https://github.com/m008v/fbchat-v2.git@<tag>"
RUN pip install -r requirements.txt
COPY app.py .
RUN useradd -r -u 10001 bridge && mkdir -p /data && chown bridge /data
USER bridge
VOLUME /data
EXPOSE 8091
# one worker: sessions and per-shop locks live in memory
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8091", "--workers", "1"]
