package com.nous.buildkit;

/** Ошибка пользовательской команды: текст показывается отправителю как есть. */
public final class CommandError extends RuntimeException {
    public CommandError(String message) {
        super(message);
    }
}
