FROM node:24-alpine AS build
WORKDIR /app
ARG VITE_MOCK_BANK_API_URL=http://localhost:8081
ENV VITE_MOCK_BANK_API_URL=$VITE_MOCK_BANK_API_URL
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
