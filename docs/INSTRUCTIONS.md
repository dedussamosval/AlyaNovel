# AlyaNovel — инструкции

## 1. Админ-панель

Адрес: https://ваш-домен/admin

Что делать:
- Войти под логином и паролем админа.
- Не передавать логин/пароль другим.
- Работать только по HTTPS.

### Создать том

```bash
В админ-панели:
1. Открыть раздел "Новый том"
2. Выбрать тип: Ранобэ или Манга
3. Указать номер и название
4. Нажать "Создать том"
```

Добавить / заменить главу

```bash
1. Выбрать том
2. Выбрать тип главы: глава / пролог / эпилог / экстра
3. Для главы и экстра указать номер
4. Для пролога и эпилога номер не нужен
5. Ввести название главы
6. Для ранобэ вставить текст главы
7. Для манги загрузить страницы (.jpg, .png, .webp)
8. Нажать "Сохранить главу"
```
Важно:

- Повторная загрузка главы с теми же томом, типом и номером заменяет её.
- Удаление тома удаляет все его главы и файлы.
- Удаление главы удаляет только эту главу.

Настройки админа
```bash
В админ-панели:
1. Открыть "Настройки"
2. Ввести текущий пароль
3. Заполнить новый логин / новый пароль, если нужно
4. Сохранить
5. Выйти после работы
```
Требование: новый пароль минимум 12 символов.

---
## 2. Развёртывание сайта на домене и VPS

**На случай если решите сами начать управлять сайтом и сервером**

Ниже пример для Ubuntu + Nginx + Certbot.

### 1) Подготовить VPS
```bash
sudo apt update                                      # обновить пакеты Ubuntu
sudo apt install -y git python3 python3-venv python3-pip nginx certbot python3-certbot-nginx
# установить: git, Python, виртуальное окружение, pip, Nginx, Certbot

sudo adduser --system --group --home /opt/alyanovel --shell /usr/sbin/nologin alya
# создать системного пользователя для проекта

sudo -u alya git clone https://github.com/dedussamosval/AlyaNovel.git /opt/alyanovel
# скачать проект в рабочую папку

sudo -u alya python3 -m venv /opt/alyanovel/.venv
# создать виртуальное окружение Python

sudo -u alya /opt/alyanovel/.venv/bin/pip install -r /opt/alyanovel/requirements.txt
# установить зависимости сервера
```

### 2) Настроить переменные окружения

```bash
python3 -c 'import secrets; print(secrets.token_hex(32))'
# сгенерировать SECRET_KEY

sudo -u alya nano /opt/alyanovel/.env
# создать файл .env с настройками проекта
```
Пример с .env
```dotenv
SECRET_KEY=ВАШ_СЛУЧАЙНЫЙ_КЛЮЧ
HTTPS_ONLY=1
PUBLIC_URL=https://example.com
DB_PATH=/opt/alyanovel/server/alyanovel.db
STORAGE_DIR=/opt/alyanovel/storage/chapters
BOT_TOKEN=TOKEN_FROM_BOTFATHER
BOT_USERNAME=my_bot
```
```bash
sudo chmod 600 /opt/alyanovel/.env
# ограничить доступ к файлу с секретами
```

### 3) Создать администратора

```bash
cd /opt/alyanovel
sudo -u alya env DB_PATH=/opt/alyanovel/server/alyanovel.db STORAGE_DIR=/opt/alyanovel/storage/chapters /opt/alyanovel/.venv/bin/python -m server.db create-admin admin
# создать первого админа с логином admin
```
В процессе команда попросит ввести пароль. Используйте пароль минимум 12 символов.

### 4) Запустить сервер как сервис

```bash
sudo nano /etc/systemd/system/alyanovel.service
# создать unit-файл сервиса
```

Содержимое файла:

```ini
[Unit]
Description=AlyaNovel FastAPI server
After=network.target

[Service]
User=alya
Group=alya
WorkingDirectory=/opt/alyanovel
EnvironmentFile=/opt/alyanovel/.env
ExecStart=/opt/alyanovel/.venv/bin/uvicorn server.main:app --proxy-headers --forwarded-allow-ips 127.0.0.1 --host 127.0.0.1 --port 8000
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
# перечитать конфигурацию systemd

sudo systemctl enable --now alyanovel
# включить автозапуск и запустить сервис

sudo systemctl status alyanovel
# проверить состояние сервиса

sudo journalctl -u alyanovel -n 100 --no-pager
# посмотреть логи, если что-то пошло не так
```

### 5) Настроить Nginx и HTTPS

```bash
sudo nano /etc/nginx/sites-available/alyanovel
# создать конфиг сайта
```

Пример
```nginx
server {
    listen 80;
    server_name example.com;
    client_max_body_size 300m;

    location /ws/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/alyanovel /etc/nginx/sites-enabled/alyanovel
# включить сайт в Nginx

sudo nginx -t
# проверить конфигурацию

sudo systemctl reload nginx
# перезагрузить Nginx

sudo certbot --nginx -d example.com
# получить SSL-сертификат и настроить HTTPS
```

После этого сайт должен быть доступен:

- https://example.com
- https://example.com/admin