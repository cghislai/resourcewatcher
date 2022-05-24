package com.charlyghislain.resourcewatcher;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StringUtils {

    private static final String ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String NUMERIC_CHARS = "0123456789";


    public static String getRandomAlphanumericString(int size) {
        return getRandomString(size);
    }


    public static String getRandomNumericString(int size) {
        return getRandomString(size, NUMERIC_CHARS);
    }

    private static String getRandomString(int length) {
        return getRandomString(length, ALPHANUMERIC_CHARS);
    }

    private static String getRandomString(int length, String allChars) {
        return Stream.generate(() -> StringUtils.getNextRandomChar(allChars))
                .limit(length)
                .map(String::valueOf)
                .collect(Collectors.joining());
    }

    private static char getNextRandomChar(String alLChars) {
        Random random = new Random();
        int randomCharIndex = random.nextInt(alLChars.length());
        char randomChar = alLChars.charAt(randomCharIndex);
        return randomChar;
    }
}
