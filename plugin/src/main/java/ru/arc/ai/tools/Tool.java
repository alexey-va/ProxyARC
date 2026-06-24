package ru.arc.ai.tools;

import ru.arc.ai.Assistant;

import javax.annotation.Nullable;

public interface Tool {
    Object execute(@Nullable Assistant assistant);
}
