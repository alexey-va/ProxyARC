package ru.arc.xserver.adapters;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.Arrays;

public class PolymorphismAdapter<T> implements JsonSerializer<T>, JsonDeserializer<T> {
    Gson gson = new Gson();

    @Override
    public T deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
        try {
            Class<?> typeClass = extract((Class<?>) type);
            JsonType jsonType = typeClass.getDeclaredAnnotation(JsonType.class);
            String property = json.getAsJsonObject().get(jsonType.property()).getAsString();
            JsonSubtype[] subtypes = jsonType.subtypes();
            Type subType = Arrays.stream(subtypes)
                    .filter((subtype) -> subtype.name().equals(property))
                    .findFirst()
                    .orElseThrow(IllegalArgumentException::new).clazz();
            return gson.fromJson(json, subType);
        } catch (Exception var9) {
            Exception e = var9;
            throw new JsonParseException("Failed deserialize json", e);
        }
    }

    @Override
    public JsonElement serialize(T t, Type type, JsonSerializationContext context) {
        Class<?> parentAbstractClass = extract((Class<?>) type);
        if (!parentAbstractClass.isAnnotationPresent(JsonType.class)) {
            return gson.toJsonTree(t);
        }
        JsonType jsonType = parentAbstractClass.getDeclaredAnnotation(JsonType.class);
        String property = jsonType.property();

        for (JsonSubtype subtype : jsonType.subtypes()) {
            if (subtype.clazz().isInstance(t)) {
                JsonObject jsonObject = gson.toJsonTree(t).getAsJsonObject();
                jsonObject.addProperty(property, subtype.name());
                return jsonObject;
            }
        }
        return gson.toJsonTree(t);
    }

    private static Class<?> extract(Class<?> type) {
        Class<?> parentAbstractClass = type;
        while (!parentAbstractClass.isAnnotationPresent(JsonType.class)) {
            Class<?> superClass = parentAbstractClass.getSuperclass();
            if (superClass == null) break;
            parentAbstractClass = superClass;
        }
        return parentAbstractClass;
    }
}
