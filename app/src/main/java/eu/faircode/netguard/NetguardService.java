package eu.faircode.netguard;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;


/**
 * Created by Amit S.
 */
public class NetguardService extends Service {
    private static final String TAG = NetguardService.class.getSimpleName();
    public static final String UPDATE_ACTION = "eu.netguard.app.UPDATE";
    public static final String UPDATE_URL = "eu.netguard.app.UPDATE_URL";
    private static final String STOP_ACTION = "eu.netguard.app.STOP";
    private static final String START_ACTION = "eu.netguard.app.START";
    
    private boolean isUpdateInProgress = false;
    
    public static void startUpdateService(Context applicationContext, Uri uri) {
        Intent intent = new Intent(applicationContext, NetguardService.class);
        //TODO: Uncomment this and handle it differently - from OS app ? or FieldX ?
        intent.setAction(NetguardService.UPDATE_ACTION);
        intent.setData(uri);
        ContextCompat.startForegroundService(applicationContext, intent);
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Intent received: " + intent);
        executeInBackground(intent);
        
        return START_STICKY;
    }
    
    private void executeInBackground(final Intent intent) {
        new Thread() {
            
            @Override
            public void run() {
                switch (intent.getAction()) {
                    case STOP_ACTION: {
                        stopNetguard();
                        break;
                    }
                    case START_ACTION: {
                        startNetguard();
                        break;
                    }
                    case UPDATE_ACTION: {
                        updateNetguardConfig(intent.getData());
                        break;
                    }
                }
            }
        }.start();
    }
    
    private void startNetguard() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("enabled", true).apply();
        ServiceSinkhole.start("receiver", this);
    }
    
    private void stopNetguard() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("enabled", false).apply();
        ServiceSinkhole.stop("receiver", this, true);
    }
    
    private void updateNetguardConfig(Uri data) {
        try {
            if (isUpdateInProgress) {
                return;
            }
            isUpdateInProgress = true;
            startForeground(0x101, getNotification());
            //This might have been from WorkManager or when user tapped the notification icon.
            //Check preferences for the uRL and use it for further processing.
            if (data==null) {
            
            }
            
            if (data.getScheme().startsWith("http")) {
                updateFromNetwork(data.toString());
            } else if (data.getScheme().startsWith("content")) {
                updateFromLocalConfig(data);
            }
            stopForeground(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        isUpdateInProgress = false;
    }
    
    private boolean updateFromNetwork(String url) {
        String config = NetworkConfigDownloadUtil.readConfigFile(this, url);
        if (config==null) {
            return false;
        }
        ByteArrayInputStream bis = new ByteArrayInputStream(config.getBytes());
        handleImport(bis);
        return true;
    }
    
    private void updateFromLocalConfig(Uri data) throws Exception {
        ContentResolver resolver = getContentResolver();
        String[] streamTypes = resolver.getStreamTypes(data, "*/*");
        String streamType = (streamTypes==null || streamTypes.length==0 ? "*/*":streamTypes[0]);
        AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(data, streamType, null);
        InputStream in = descriptor.createInputStream();
        handleImport(in);
    }
    
    private Notification getNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "foreground");
        builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false);
        builder.setContentTitle("Importing VPN config");
        return builder.build();
    }
    
    private void handleImport(final InputStream in) {
        try {
            stopNetguard();
            Thread.sleep(7000);
            xmlImport(in);
            ServiceSinkhole.reloadStats("import", NetguardService.this);
            startNetguard();
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        } finally {
            if (in!=null)
                try {
                    in.close();
                } catch (IOException ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
        }
    }
    
    
    private void xmlImport(InputStream in) throws IOException, SAXException, ParserConfigurationException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        XmlImportHandler handler = new XmlImportHandler(this);
        reader.setContentHandler(handler);
        reader.parse(new InputSource(in));
        
        xmlImport(handler.application, prefs);
        xmlImport(handler.wifi, getSharedPreferences("wifi", Context.MODE_PRIVATE));
        xmlImport(handler.mobile, getSharedPreferences("other", Context.MODE_PRIVATE));
        xmlImport(handler.screen_wifi, getSharedPreferences("screen_wifi", Context.MODE_PRIVATE));
        xmlImport(handler.screen_other, getSharedPreferences("screen_other", Context.MODE_PRIVATE));
        xmlImport(handler.roaming, getSharedPreferences("roaming", Context.MODE_PRIVATE));
        xmlImport(handler.lockdown, getSharedPreferences("lockdown", Context.MODE_PRIVATE));
        xmlImport(handler.apply, getSharedPreferences("apply", Context.MODE_PRIVATE));
        xmlImport(handler.notify, getSharedPreferences("notify", Context.MODE_PRIVATE));
        
        // Upgrade imported settings
        ReceiverAutostart.upgrade(true, this);
        
        DatabaseHelper.clearCache();
        
        // Refresh UI
        prefs.edit().putBoolean("imported", true).apply();
        //prefs.registerOnSharedPreferenceChangeListener(this);
    }
    
    private void xmlImport(Map<String, Object> settings, SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();
        
        // Clear existing setting
        for (String key : prefs.getAll().keySet())
            if (!"enabled".equals(key))
                editor.remove(key);
        
        // Apply new settings
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer)
                editor.putInt(key, (Integer) value);
            else if (value instanceof String)
                editor.putString(key, (String) value);
            else if (value instanceof Set)
                editor.putStringSet(key, (Set<String>) value);
            else
                Log.e(TAG, "Unknown type=" + value.getClass());
        }
        
        editor.apply();
    }
    
    
    private class XmlImportHandler extends DefaultHandler {
        private Context context;
        public boolean enabled = false;
        public Map<String, Object> application = new HashMap<>();
        public Map<String, Object> wifi = new HashMap<>();
        public Map<String, Object> mobile = new HashMap<>();
        public Map<String, Object> screen_wifi = new HashMap<>();
        public Map<String, Object> screen_other = new HashMap<>();
        public Map<String, Object> roaming = new HashMap<>();
        public Map<String, Object> lockdown = new HashMap<>();
        public Map<String, Object> apply = new HashMap<>();
        public Map<String, Object> notify = new HashMap<>();
        private Map<String, Object> current = null;
        
        public XmlImportHandler(Context context) {
            this.context = context;
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (qName.equals("netguard"))
                ; // Ignore
            
            else if (qName.equals("application"))
                current = application;
            
            else if (qName.equals("wifi"))
                current = wifi;
            
            else if (qName.equals("mobile"))
                current = mobile;
            
            else if (qName.equals("screen_wifi"))
                current = screen_wifi;
            
            else if (qName.equals("screen_other"))
                current = screen_other;
            
            else if (qName.equals("roaming"))
                current = roaming;
            
            else if (qName.equals("lockdown"))
                current = lockdown;
            
            else if (qName.equals("apply"))
                current = apply;
            
            else if (qName.equals("notify"))
                current = notify;
            
            else if (qName.equals("filter")) {
                current = null;
                Log.i(TAG, "Clearing filters");
                DatabaseHelper.getInstance(context).clearAccess();
                
            } else if (qName.equals("forward")) {
                current = null;
                Log.i(TAG, "Clearing forwards");
                DatabaseHelper.getInstance(context).deleteForward();
                
            } else if (qName.equals("setting")) {
                String key = attributes.getValue("key");
                String type = attributes.getValue("type");
                String value = attributes.getValue("value");
                
                if (current==null)
                    Log.e(TAG, "No current key=" + key);
                else {
                    if ("enabled".equals(key))
                        enabled = Boolean.parseBoolean(value);
                    else {
                        if (current==application) {
                            // Pro features
                            if ("log".equals(key)) {
                                if (!IAB.isPurchased(ActivityPro.SKU_LOG, context))
                                    return;
                            } else if ("theme".equals(key)) {
                                if (!IAB.isPurchased(ActivityPro.SKU_THEME, context))
                                    return;
                            } else if ("show_stats".equals(key)) {
                                if (!IAB.isPurchased(ActivityPro.SKU_SPEED, context))
                                    return;
                            }
                            
                            if ("hosts_last_import".equals(key) || "hosts_last_download".equals(key))
                                return;
                        }
                        
                        if ("boolean".equals(type))
                            current.put(key, Boolean.parseBoolean(value));
                        else if ("integer".equals(type))
                            current.put(key, Integer.parseInt(value));
                        else if ("string".equals(type))
                            current.put(key, value);
                        else if ("set".equals(type)) {
                            Set<String> set = new HashSet<>();
                            if (!TextUtils.isEmpty(value))
                                for (String s : value.split("\n"))
                                    set.add(s);
                            current.put(key, set);
                        } else
                            Log.e(TAG, "Unknown type key=" + key);
                    }
                }
                
            } else if (qName.equals("rule")) {
                String pkg = attributes.getValue("pkg");
                
                String version = attributes.getValue("version");
                String protocol = attributes.getValue("protocol");
                
                Packet packet = new Packet();
                packet.version = (version==null ? 4:Integer.parseInt(version));
                packet.protocol = (protocol==null ? 6 /* TCP */:Integer.parseInt(protocol));
                packet.daddr = attributes.getValue("daddr");
                packet.dport = Integer.parseInt(attributes.getValue("dport"));
                packet.time = Long.parseLong(attributes.getValue("time"));
                
                int block = Integer.parseInt(attributes.getValue("block"));
                
                try {
                    packet.uid = getUid(pkg);
                    DatabaseHelper.getInstance(context).updateAccess(packet, null, block);
                } catch (PackageManager.NameNotFoundException ex) {
                    Log.w(TAG, "Package not found pkg=" + pkg);
                }
                
            } else if (qName.equals("port")) {
                String pkg = attributes.getValue("pkg");
                int protocol = Integer.parseInt(attributes.getValue("protocol"));
                int dport = Integer.parseInt(attributes.getValue("dport"));
                String raddr = attributes.getValue("raddr");
                int rport = Integer.parseInt(attributes.getValue("rport"));
                
                try {
                    int uid = getUid(pkg);
                    DatabaseHelper.getInstance(context).addForward(protocol, dport, raddr, rport, uid);
                } catch (PackageManager.NameNotFoundException ex) {
                    Log.w(TAG, "Package not found pkg=" + pkg);
                }
                
            } else
                Log.e(TAG, "Unknown element qname=" + qName);
        }
        
        private int getUid(String pkg) throws PackageManager.NameNotFoundException {
            if ("root".equals(pkg))
                return 0;
            else if ("android.media".equals(pkg))
                return 1013;
            else if ("android.multicast".equals(pkg))
                return 1020;
            else if ("android.gps".equals(pkg))
                return 1021;
            else if ("android.dns".equals(pkg))
                return 1051;
            else if ("nobody".equals(pkg))
                return 9999;
            else
                return getPackageManager().getApplicationInfo(pkg, 0).uid;
        }
    }
    
}
