"""Логгер для записи в файл
   При запуске приложения добавьте аргумент --log-config uvicorn_logger.yaml
"""
import logging
import os
from datetime import datetime

class DatedFileHandler(logging.FileHandler):
    def __init__(self, filename, mode='a', encoding='utf-8', delay=False):
        current_date = datetime.now().strftime("%d-%m-%y-%H%M")
        
        final_filename = filename.format(date=current_date)
        
        os.makedirs(os.path.dirname(final_filename), exist_ok=True)
        
        super().__init__(final_filename, mode, encoding, delay)
