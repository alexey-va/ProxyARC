package ru.arc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Common {

    public static Gson gson = new GsonBuilder()
            .create();
    public static Gson prettyGson = new GsonBuilder().setPrettyPrinting()
            .create();

}
