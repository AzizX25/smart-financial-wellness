FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
COPY models ./models
ENV FORECAST_MODEL_DIRECTORY=/app/models/forecast
ENV MERCHANT_MODEL_DIRECTORY=/app/models/merchant
RUN pip install --no-cache-dir .

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000
CMD ["uvicorn", "forecast_ai.main:app", "--host", "0.0.0.0", "--port", "8000"]
