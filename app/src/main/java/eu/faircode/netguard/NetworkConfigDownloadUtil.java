package eu.faircode.netguard;


import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.text.SimpleDateFormat;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by Amit S.
 * <p>
 * TODO:
 * - Directly write the config xml file to a local file and read config from there
 */
public class NetworkConfigDownloadUtil {
    
    public static String readConfigFile(Context context, String url) {
        //Eg: header If-Modified-Since = Tue, 16 Mar 2021 06:50:04 GMT
        OkHttpClient client = new OkHttpClient();
        
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        long lastSyncTime = preferences.getLong("lastSyncTime", 0);
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z");
        
        Request request = new Request.Builder()
                                  .url(url)
                                  .addHeader("If-Modified-Since", sdf.format(lastSyncTime))
                                  .build();
        try (Response response = client.newCall(request).execute()) {
//            if (response.code()==HttpURLConnection.HTTP_NOT_MODIFIED) {
//                return null;
//            }
            
            String config = response.body().string();
            if (config!=null) {
                preferences.edit().putLong("lastSyncTime", System.currentTimeMillis()).apply();
                return config;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
