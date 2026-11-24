# Contributing & Quality Standards

## Требования к коду

1. **Java 25 LTS & строгое разделение слоёв:**
   - В пакетах `domain` и `application` запрещены зависимости от HTTP, Jackson, SLF4J и файлового I/O.
   - Запрещено использовать `ObjectInputStream` (CWE-502).
   - Запрещены `System.out` / `System.err` в production-коде (`Checkstyle` проверяет автоматически); для аудита и логов используется SLF4J.
2. **Форматирование и статический анализ:**
   - Стиль кода задан [config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml), правилами PMD ([config/pmd/ruleset.xml](config/pmd/ruleset.xml), цикломатическая сложность <= 10), SpotBugs и `.editorconfig`.
   - Автоформатирование выполняется через `make format` (`palantir-java-format`).
3. **Тестирование и покрытие:**
   - Unit-тесты в `unit/` не поднимают HTTP-сервер или внешние порты.
   - Порог покрытия `JaCoCo` >= 80% строк для пакетов `domain` и `application`.

## Локальный цикл перед коммитом

```bash
make install-hooks   # один раз активирует .githooks/pre-commit
make format          # отформатировать исходники
make check           # прогнать все линтеры, тесты, JaCoCo и сборку JAR
```
