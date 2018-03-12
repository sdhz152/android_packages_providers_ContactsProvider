/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.contacts;

import android.icu.text.Transliterator;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;


/**
 * An object to convert Chinese character to its corresponding pinyin string.
 * For characters with multiple possible pinyin string, only one is selected
 * according to ICU Transliterator class. Polyphone is not supported in this
 * implementation.
 */
public class HanziToPinyin {
    private static final String TAG = "HanziToPinyin";

    private static HanziToPinyin sInstance;
    private Transliterator mPinyinTransliterator;
    private Transliterator mAsciiTransliterator;

    private static final HashMap<String, String> POLYPHONES = new HashMap<>();
    static {
        POLYPHONES.put("夏", "XIA");
        POLYPHONES.put("瞿", "QU");
        POLYPHONES.put("曾", "ZENG");
        POLYPHONES.put("石", "SHI");
        POLYPHONES.put("解", "XIE");
        POLYPHONES.put("藏", "ZANG");
        POLYPHONES.put("翟", "ZHAI");
        POLYPHONES.put("都", "DU");
        POLYPHONES.put("六", "LU");
        POLYPHONES.put("薄", "BO");
        POLYPHONES.put("贾", "JIA");
        POLYPHONES.put("居", "JU");
        POLYPHONES.put("查", "ZHA");
        POLYPHONES.put("盛", "SHENG");
        POLYPHONES.put("塔", "TA");
        POLYPHONES.put("和", "HE");
        POLYPHONES.put("蓝", "LAN");
        POLYPHONES.put("殷", "YIN");
        POLYPHONES.put("乾", "QIAN");
        POLYPHONES.put("陆", "LU");
        POLYPHONES.put("乜", "NIE");
        POLYPHONES.put("阚", "KAN");
        POLYPHONES.put("叶", "YE");
        POLYPHONES.put("强", "QIANG");
        POLYPHONES.put("汤", "TANG");
        POLYPHONES.put("万", "WAN");
        POLYPHONES.put("沈", "SHEN");
        POLYPHONES.put("仇", "QIU");
        POLYPHONES.put("南", "NAN");
        POLYPHONES.put("单", "SHAN");
        POLYPHONES.put("卜", "BU");
        POLYPHONES.put("鸟", "NIAO");
        POLYPHONES.put("思", "SI");
        POLYPHONES.put("寻", "XUN");
        POLYPHONES.put("於", "YU");
        POLYPHONES.put("余", "YU");
        POLYPHONES.put("浅", "QIAN");
        POLYPHONES.put("浣", "WAN");
        POLYPHONES.put("无", "WU");
        POLYPHONES.put("信", "XIN");
        POLYPHONES.put("許", "XU");
        POLYPHONES.put("齐", "QI");
        POLYPHONES.put("俞", "YU");
        POLYPHONES.put("若", "RUO");
    }

    public static class Token {
        /**
         * Separator between target string for each source char
         */
        public static final String SEPARATOR = " ";

        public static final int LATIN = 1;
        public static final int PINYIN = 2;
        public static final int UNKNOWN = 3;

        public Token() {
        }

        public Token(int type, String source, String target) {
            this.type = type;
            this.source = source;
            this.target = target;
        }

        /**
         * Type of this token, ASCII, PINYIN or UNKNOWN.
         */
        public int type;
        /**
         * Original string before translation.
         */
        public String source;
        /**
         * Translated string of source. For Han, target is corresponding Pinyin. Otherwise target is
         * original string in source.
         */
        public String target;
    }

    private HanziToPinyin() {
        try {
            mPinyinTransliterator = Transliterator.getInstance(
                    "Han-Latin/Names; Latin-Ascii; Any-Upper");
            mAsciiTransliterator = Transliterator.getInstance("Latin-Ascii");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Han-Latin/Names transliterator data is missing,"
                  + " HanziToPinyin is disabled");
        }
    }

    public boolean hasChineseTransliterator() {
        return mPinyinTransliterator != null;
    }

    public static HanziToPinyin getInstance() {
        synchronized (HanziToPinyin.class) {
            if (sInstance == null) {
                sInstance = new HanziToPinyin();
            }
            return sInstance;
        }
    }

    /**
     * Check if the first name is multi-pinyin
     *
     * @return right pinyin for this first name
     */
    private static String getMPinyin(final String firstName) {
        return POLYPHONES.get(firstName);
    }

    private void tokenize(char character, Token token, boolean isFirstName) {
        token.source = Character.toString(character);

        // ASCII
        if (character < 128) {
            token.type = Token.LATIN;
            token.target = token.source;
            return;
        }

        // Extended Latin. Transcode these to ASCII equivalents
        if (character < 0x250 || (0x1e00 <= character && character < 0x1eff)) {
            token.type = Token.LATIN;
            token.target = mAsciiTransliterator == null ? token.source :
                mAsciiTransliterator.transliterate(token.source);
            return;
        }

        token.type = Token.PINYIN;

        if (isFirstName) {
            token.target = getMPinyin(Character.toString(character));
            if (token.target != null) {
                return;
            }
        }

        token.target = mPinyinTransliterator.transliterate(token.source);
        if (TextUtils.isEmpty(token.target) ||
            TextUtils.equals(token.source, token.target)) {
            token.type = Token.UNKNOWN;
            token.target = token.source;
        }
    }

    public String transliterate(final String input) {
        if (!hasChineseTransliterator() || TextUtils.isEmpty(input)) {
            return null;
        }
        return mPinyinTransliterator.transliterate(input);
    }

    /**
     * Convert the input to a array of tokens. The sequence of ASCII or Unknown characters without
     * space will be put into a Token, One Hanzi character which has pinyin will be treated as a
     * Token. If there is no Chinese transliterator, the empty token array is returned.
     */
    public ArrayList<Token> getTokens(final String input) {
        ArrayList<Token> tokens = new ArrayList<Token>();
        if (!hasChineseTransliterator() || TextUtils.isEmpty(input)) {
            // return empty tokens.
            return tokens;
        }

        final int inputLength = input.length();
        final StringBuilder sb = new StringBuilder();
        int tokenType = Token.LATIN;
        Token token = new Token();
        boolean firstname;

        // Go through the input, create a new token when
        // a. Token type changed
        // b. Get the Pinyin of current charater.
        // c. current character is space.
        for (int i = 0; i < inputLength; i++) {
            final char character = input.charAt(i);
            if (Character.isSpaceChar(character)) {
                if (sb.length() > 0) {
                    addToken(sb, tokens, tokenType);
                }
            } else {
                tokenize(character, token, i == 0);
                if (token.type == Token.PINYIN) {
                    if (sb.length() > 0) {
                        addToken(sb, tokens, tokenType);
                    }
                    tokens.add(token);
                    token = new Token();
                } else {
                    if (tokenType != token.type && sb.length() > 0) {
                        addToken(sb, tokens, tokenType);
                    }
                    sb.append(token.target);
                }
                tokenType = token.type;
            }
        }
        if (sb.length() > 0) {
            addToken(sb, tokens, tokenType);
        }
        return tokens;
    }

    private void addToken(
            final StringBuilder sb, final ArrayList<Token> tokens, final int tokenType) {
        String str = sb.toString();
        tokens.add(new Token(tokenType, str, str));
        sb.setLength(0);
    }
}
