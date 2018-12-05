FROM nginx:1.15.2-alpine

ARG CONTAINER_PORT

RUN mkdir -p /etc/nginx/www

COPY build /etc/nginx/www/

COPY nginx.conf /etc/nginx/nginx.conf

WORKDIR /etc/nginx/www

EXPOSE ${CONTAINER_PORT}

CMD ["nginx", "-g", "daemon off;"]
