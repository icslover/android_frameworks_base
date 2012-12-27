/*
 * Copyright (C) 2012 Imil Ziyaztdinov
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

package android.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.provider.Settings;

import com.android.internal.R;

import java.lang.Math;
import java.math.BigInteger;

public class ColorUtils {

    public static final int[] AVAILABLE_COLORS = {
//            R.color.black, // to be or not to be?
            R.color.holo_blue_bright,
            R.color.holo_blue_dark,
            R.color.holo_blue_light,
            R.color.holo_green_dark,
            R.color.holo_green_light,
            R.color.holo_orange_dark,
            R.color.holo_orange_light,
            R.color.holo_purple,
            R.color.holo_red_dark,
            R.color.holo_red_light
    };

    public static class ColorSettingInfo {
        public String currentSetting;
        public int currentIndex;

        public String systemColorString;
        public String currentColorString;
        public String lastColorString;
        
        public int systemColor;
        public int currentColor;
        public int lastColor;
        public int defaultColor;
        
        public int speed;

        public boolean isSystemColorNull;
        public boolean isCurrentColorNull;
        public boolean isLastColorNull;

        public boolean isSystemColorOpaque;
        public boolean isCurrentColorOpaque;
        public boolean isLastColorOpaque;
    }

    public static final String NO_COLOR = "null|null|0";
    public static final int HOLO_BLUE = 0xFF33B5E5;

    private static final double COMPARATIVE_FACTOR = 3.5;
    private static final double COMPARATIVE_NUMBER = COMPARATIVE_FACTOR * 125;
    private static final double BLACK_OFFSET = 15;
    
    public static void SetColor(Context context, String settingName, String systemColor,
            String currentColor, int index) {
        Settings.System.putString(context.getContentResolver(), settingName, 
                systemColor + "|" + currentColor + "|" + index);
    }

    public static void SetColor(Context context, String settingName, String systemColor,
            String currentColor, int index, int speed) {
        Settings.System.putString(context.getContentResolver(), settingName, 
                systemColor + "|" + currentColor + "|" + index + "|" + speed);
    }

    public static ColorSettingInfo GetColorSettingInfo(Context context, String settingName) {
        ColorSettingInfo Result = new ColorSettingInfo();

        // Get setting and parse
        Result.currentSetting = Settings.System.getString(context.getContentResolver(), settingName);
        String[] colors = (Result.currentSetting == null || Result.currentSetting.equals("")  ?
                ColorUtils.NO_COLOR : Result.currentSetting).split(
                ExtendedPropertiesUtils.PARANOID_STRING_DELIMITER);

        // Sanity check
        if (colors.length < 3) {
            Settings.System.putString(context.getContentResolver(), settingName, ColorUtils.NO_COLOR);
            colors = ColorUtils.NO_COLOR.split(ExtendedPropertiesUtils.PARANOID_STRING_DELIMITER);
        }

        // Get index
        Result.currentIndex = Integer.parseInt(colors[2]);

        // Get color strings
        Result.systemColorString = colors[0];
        Result.currentColorString = colors[1];
        Result.lastColorString = colors[Result.currentIndex];

        // Check if null
        Result.isSystemColorNull = Result.systemColorString.equals("null");
        Result.isCurrentColorNull = Result.currentColorString.equals("null");
        Result.isLastColorNull =  Result.currentIndex == 0 ? Result.isSystemColorNull :
                Result.isCurrentColorNull;

        // Get speed
        Result.speed = colors.length < 4 ? 500 : Integer.parseInt(colors[3]);

        // Get default color
        for(int i = 0; i < ExtendedPropertiesUtils.PARANOID_COLORS_SETTINGS.length; i++) {
            if (ExtendedPropertiesUtils.PARANOID_COLORS_SETTINGS[i] == settingName) {
                Result.defaultColor = ExtendedPropertiesUtils.PARANOID_COLORCODES_DEFAULTS[i];
                break;
            }
        }

        // Get color values
        Result.systemColor = Result.isSystemColorNull ? Result.defaultColor :
                new BigInteger(Result.systemColorString, 16).intValue();
        Result.currentColor = Result.isCurrentColorNull ? Result.defaultColor :
                new BigInteger(Result.currentColorString, 16).intValue();
        Result.lastColor = Result.currentIndex == 0 ? Result.systemColor : Result.currentColor;

        // Check alpha state
        Result.isSystemColorOpaque = (Result.systemColor & 0xFF000000) == 0xFF000000;
        Result.isCurrentColorOpaque = (Result.currentColor & 0xFF000000) == 0xFF000000;
        Result.isLastColorOpaque = (Result.lastColor & 0xFF000000) == 0xFF000000;

        // Return structure
        return Result;
    }

    public static int extractRGB(int color) {
        return color & 0x00FFFFFF;
    }

    public static int extractAlpha(int color) {
        return color >> 24;
    }

    private static int getColorLuminance(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Math.round(((red * 299) + (green * 587)
                +(blue * 114)) / 1000);  
    }

    private static int getLuminanceDifference(int color1, int color2) {
        int lum1 = getColorLuminance(color1);
        int lum2 = getColorLuminance(color2);
        return Math.abs(lum1 - lum2);
    }

    private static int getColorDifference(int color1, int color2) {
        int[] rgb1 = {Color.red(color1), Color.green(color1), Color.blue(color1)};
        int[] rgb2 = {Color.red(color2), Color.green(color2), Color.blue(color2)}; 
        return Math.abs(rgb1[0] - rgb2[0]) +
               Math.abs(rgb1[1] - rgb2[1]) +
               Math.abs(rgb1[2] - rgb2[2]); 
    }  

    public static int getComplementaryColor(int bgcolor, Context context) {
        Resources res = context.getResources();
        // We cannot check if it equals to black (because of alpha layer)
        // so we check each color individually
        if(Color.red(bgcolor) < BLACK_OFFSET
                && Color.green(bgcolor) < BLACK_OFFSET
                && Color.blue(bgcolor) < BLACK_OFFSET) {
            return res.getColor(R.color.holo_blue_dark);
        }
        int minKey = 0;
        double lumDiff = 0;
        double colDiff = 0;
        double currValue = 0;
        double minValue = -1;
        for (int i = 0; i < AVAILABLE_COLORS.length; i++) {
            lumDiff = COMPARATIVE_FACTOR * getLuminanceDifference(bgcolor,
                    res.getColor(AVAILABLE_COLORS[i]));
            colDiff = getColorDifference(bgcolor,
                    res.getColor(AVAILABLE_COLORS[i]));
            lumDiff = Math.abs(COMPARATIVE_NUMBER - lumDiff);
            colDiff = Math.abs(COMPARATIVE_NUMBER - colDiff);
            currValue = lumDiff + colDiff;
            if (minValue == -1 || currValue < minValue) {
                minKey = i;
                minValue = currValue;
            }
        }      
        return res.getColor(AVAILABLE_COLORS[minKey]);
    }
}
