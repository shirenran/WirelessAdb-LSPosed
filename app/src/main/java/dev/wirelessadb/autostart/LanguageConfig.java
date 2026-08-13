package dev.wirelessadb.autostart;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Stores the optional app language override and wraps the activity context. */
public final class LanguageConfig {
    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_PT_BR = "pt-BR";
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_ES = "es";
    public static final String LANGUAGE_ZH = "zh";

    private static final String PREFS = "settings";
    private static final String KEY_LANGUAGE = "language";
    private static final String[] LANGUAGES = {
            LANGUAGE_SYSTEM, LANGUAGE_PT_BR, LANGUAGE_EN, LANGUAGE_ES, LANGUAGE_ZH
    };

    private LanguageConfig() {}

    public static String getLanguage(Context context) {
        String language = prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM);
        return isSupported(language) ? language : LANGUAGE_SYSTEM;
    }

    public static void setLanguage(Context context, String language) {
        prefs(context).edit().putString(KEY_LANGUAGE,
                isSupported(language) ? language : LANGUAGE_SYSTEM).apply();
    }

    public static Context wrap(Context base) {
        String language = getLanguage(base);
        if (LANGUAGE_SYSTEM.equals(language)) return base;

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(language));
        return base.createConfigurationContext(configuration);
    }

    public static int positionFor(String language) {
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(language)) return i;
        }
        return 0;
    }

    public static String languageAt(int position) {
        return position >= 0 && position < LANGUAGES.length
                ? LANGUAGES[position]
                : LANGUAGE_SYSTEM;
    }

    private static boolean isSupported(String language) {
        for (String supported : LANGUAGES) {
            if (supported.equals(language)) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
