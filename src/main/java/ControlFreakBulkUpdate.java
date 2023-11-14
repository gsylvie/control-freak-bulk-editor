import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

public class ControlFreakBulkUpdate {
    private final static SimpleDateFormat DF = new SimpleDateFormat("YYYY-MM-dd'T'HH:mm:ss.SSSZ");
    private final static SimpleDateFormat BACKUP_DF = new SimpleDateFormat("YYYY-MM-dd.HHmmss");

    public static void main(String[] args) throws Exception {

        File f = new File("./.controlFreakToken.tok");
        String token = Util.fileToString(f);
        StringBuilder buf = new StringBuilder(token.length());
        for (String line : Util.intoLines(token)) {
            line = line.trim();
            if (!"".equals(line) && !line.startsWith("#")) {
                buf.append(line);
            }
        }
        token = buf.toString();

        String user = args[0];
        String bitbucketBaseUrl = stripTrailingChar(args[1], '/');
        String bulkCommandJson = args[2];
        f = new File(bulkCommandJson);
        String json = Util.fileToString(f);
        Map m = Java2Json.parseToMap(json);

        String globalUrl = bitbucketBaseUrl + "/plugins/servlet/bb_hook_admin/get?json=true";
        final List<Object> listOrig = (List) m.remove("repos");
        final List<Object> list = Collections.unmodifiableList(
                listOrig != null ? new ArrayList(listOrig) : new ArrayList<>()
        );

        Map gMap = fetch(globalUrl, user, token);
        if (gMap.containsKey("err")) {
            System.out.println(now() + " - Bulk edit failed for user '" + user + "' - " + gMap.get("err"));
            return;
        }

        Set<String> keysToWrite = new TreeSet<>(m.keySet());
        Set<String> actualKeysToWrite = new TreeSet<>();
        Set<String> groupsToOverride = new TreeSet<>();
        Set<String> keysToInclude = new TreeSet<>();
        Object first = list.get(0);
        String repoUrl = repoUrl(bitbucketBaseUrl, first);
        final Map<String, Object> firstRepoMap = Collections.unmodifiableMap(fetch(repoUrl, user, token));
        Map<String, List<String>> overrides = (Map) firstRepoMap.get("_OVERRIDE_GROUPINGS");
        if (overrides != null) {
            for (Map.Entry<String, List<String>> entry : overrides.entrySet()) {
                String group = entry.getKey();
                List<String> keys = entry.getValue();
                if (containsAny(keysToWrite, keys, actualKeysToWrite)) {
                    groupsToOverride.add(group);
                    keysToInclude.addAll(keys);
                }
            }
            keysToInclude.removeAll(keysToWrite);
            Set<String> unknownKeys = new TreeSet<>(keysToWrite);
            unknownKeys.removeAll(actualKeysToWrite);
            if (!unknownKeys.isEmpty()) {
                System.out.println(now() + " ! Unknown keys (ignoring for bulk-edit): " + unknownKeys);
                for (String k : unknownKeys) {
                    m.remove(k);
                }
            }
            keysToWrite.clear();
            keysToWrite = Collections.unmodifiableSet(actualKeysToWrite);

            System.out.println(now() + " - Keys to bulk-edit: " + keysToWrite);
            System.out.println(now() + " - Groups to overwrite: " + groupsToOverride);
            System.out.println(now() + " - Keys to include (because from same group(s)):   " + keysToInclude);
        }

        boolean allGood = true;
        for (int i = 0; i < 2; i++) {
            if (!allGood) {
                System.out.println(now() + " * ControlFreakBulkUpdate stopping. Not all edits will succeed (see above). To be safe, we did nothing.");
                return;
            } else if (i == 1) {
                System.out.println(now() + " - ControlFreakBulkUpdate confirmed edits will work.  Proceeding with bulk-update...");
            }
            for (Object o : list) {
                Map<String, Object> keyValuesToWrite = Collections.unmodifiableMap(new TreeMap<>(m));
                Map<String, Object> keyValuesToInclude = new TreeMap<>();
                repoUrl = repoUrl(bitbucketBaseUrl, o);
                final Map<String, Object> rMap = Collections.unmodifiableMap(fetch(repoUrl, user, token));
                Long pId = (Long) rMap.get("p");
                Boolean readOnly = (Boolean) rMap.get("cf_readOnly");
                if (readOnly == null) {
                    readOnly = true;
                    allGood = false;
                }

                if (pId != null && !readOnly) {
                    String projectUrl = bitbucketBaseUrl + "/plugins/servlet/bb_hook_admin/mini/GET/get?json=true&p=" + pId;
                    Map pMap = fetch(projectUrl, user, token);
                    for (String key : keysToInclude) {
                        if (rMap.containsKey(key)) {
                            keyValuesToInclude.put(key, rMap.get(key));
                        } else if (pMap.containsKey(key)) {
                            keyValuesToInclude.put(key, pMap.get(key));
                        } else {
                            keyValuesToInclude.put(key, gMap.get(key));
                        }
                    }

                    Map<String, Object> draft = new TreeMap<>(keyValuesToInclude);
                    draft.putAll(keyValuesToWrite);
                    if (alreadySet(rMap, keyValuesToWrite)) {
                        if (i == 1) {
                            System.out.println(now() + " ! Skipping bulk-edit for " + o + " since it's already set that way.");
                        }
                    } else {
                        if (i == 1) {
                            for (String g : groupsToOverride) {

                                String backup = Java2Json.format(true, rMap);
                                String obj = o.toString().replace("/", "_");
                                File backupFile = new File("cf_backups/" + obj + "-backup-" + BACKUP_DF.format(new Date()) + ".json");
                                backupFile.getParentFile().mkdirs();
                                Util.stringToFile(backup, backupFile);
                                byte[] backupBytes = backup.getBytes(StandardCharsets.UTF_8);
                                long len = backupFile.length();
                                if (backupBytes.length != (int) len) {
                                    System.out.println(now() + " * Could not backup " + o + " config - refusing to bulk-edit");
                                    System.exit(1);
                                    return;
                                } else {
                                    System.out.println(now() + " - Saved config backup: " + backupFile.getAbsolutePath());
                                }

                                draft.put(g, Boolean.TRUE);
                                String postBody = Java2Json.format(true, draft);

                                // HTTP POST NOW !!!!
                                Map resultMap = post(repoUrl, user, token, postBody);
                                System.out.println(now() + " - Bulk-edit for " + o + " - result: " + Java2Json.format(resultMap));
                            }
                        }
                    }

                } else {
                    allGood = false;
                    String err = (String) rMap.get("err");
                    if (err != null) {
                        System.out.println(now() + " * ControlFreakBulkUpdate as user '" + user + "' cannot bulk-edit repository '" + o + "' - " + err);
                    } else {
                        System.out.println(now() + " * ControlFreakBulkUpdate as user '" + user + "' cannot bulk-edit repository '" + o + "' - cf_readOnly=" + readOnly);
                    }
                    System.exit(1);
                }
            }
        }
    }

    private static boolean alreadySet(Map<String, Object> currentVals, Map<String, Object> keyValuesToWrite) {
        for (Map.Entry<String, Object> entry : keyValuesToWrite.entrySet()) {
            String k = entry.getKey();
            Object o = entry.getValue();
            if (o == null) {
                return false;
            } else if (!o.equals(currentVals.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static String now() {
        return DF.format(new Date(System.currentTimeMillis()));
    }

    private static String repoUrl(String bitbucketBaseUrl, Object o) {
        String slug = "";
        Long repoId = -1L;
        if (o instanceof String) {
            slug = (String) o;
            slug = stripLeadingChar(slug, '/');
            slug = stripTrailingChar(slug, '/');
            return bitbucketBaseUrl + "/plugins/servlet/bb_hook_admin/mini/" + slug + "/get?json=true";
        } else {
            repoId = (Long) o;
            return bitbucketBaseUrl + "/plugins/servlet/bb_hook_admin/mini/GET/get?json=true&r=" + repoId;
        }
    }

    private static boolean containsAny(Set<String> set, List<String> objs, Set<String> hits) {
        boolean containsAny = false;
        for (String o : objs) {
            if (set.contains(o)) {
                hits.add(o);
                containsAny = true;
            }
        }
        return containsAny;
    }

    private static String stripLeadingChar(String s, char c) {
        if (s != null) {
            while (s.charAt(0) == c) {
                s = s.substring(1);
            }
        }
        return s;
    }

    private static String stripTrailingChar(String s, char c) {
        if (s != null) {
            while (s.charAt(s.length() - 1) == c) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private static Map<String, Object> post(String url, String user, String pass, String postBody) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = null;
        InputStream in = null;
        byte[] bytes = postBody.getBytes(StandardCharsets.UTF_8);
        try {
            conn = (HttpURLConnection) u.openConnection();
            String userpass = user + ":" + pass;
            String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userpass.getBytes()));
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", basicAuth);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Length", String.valueOf(bytes.length));
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.flush();
            out.close();

            in = conn.getInputStream();

            String result = Util.streamToString(in);
            return Java2Json.parseToMap(result);

        } catch (IOException ioe) {

            Map<String, Object> m = new TreeMap<>();
            m.put("err", ioe.toString());
            return m;

        } finally {
            Util.close(in, conn);
        }
    }

    private static Map<String, Object> fetch(String url, String user, String pass) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) u.openConnection();
            String userpass = user + ":" + pass;
            String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userpass.getBytes()));
            conn.setRequestProperty("Authorization", basicAuth);
            in = conn.getInputStream();

            String result = Util.streamToString(in);
            return Java2Json.parseToMap(result);

        } catch (IOException ioe) {

            Map<String, Object> m = new TreeMap<>();
            m.put("err", ioe.toString());
            return m;

        } finally {
            Util.close(in, conn);
        }
    }

    /**
     * Utility for converting back and forth between Java objects (Map, Collection, String, Number, Boolean, null) and JSON.
     */
    static class Java2Json {

        public static boolean tolerant = true;

        private int pos;
        private char[] json;

        private Java2Json(int pos, char[] json) {
            this.pos = pos;
            this.json = json;
        }

        private final static Long ZERO = Long.valueOf("0");
        private final static int MAP = 0;
        private final static int LIST = 1;
        private final static int STRING = 2;
        private final static int NUMBER = 3;
        private final static int BOOLEAN = 5;
        private final static int NULL = 6;
        private final static int MODE_WHITESPACE = -1;
        private final static int MODE_NORMAL = 0;
        private final static int MODE_BACKSLASH = 1;

        public static Map<String, Object> parseToMap(String json) {
            if (json == null) {
                return null;
            }
            json = json.trim();
            if ("".equals(json)) {
                return new LinkedHashMap<>();
            } else {
                return (Map) parse(json);
            }
        }

        /**
         * Converts a String of JSON into a Java representation,
         * parsing the result into a structure of nested
         * Map, List, Boolean, Long, Double, String and null objects.
         *
         * @param json String to parse
         * @return A Java representation of the parsed JSON String
         * based on java.util.Map, java.util.List, java.lang.Boolean,
         * java.lang.Number, java.lang.String and null.
         */
        public static Object parse(String json) {
            char[] c = json.toCharArray();
            Java2Json p = new Java2Json(0, c);

            try {
                int type = nextObject(p);
                Object o = parseObject(type, p);
                finalWhitespace(p);
                return o;

            } catch (RuntimeException re) {
                throw re;
            }
        }

        public static String format(Object o) {
            return format(false, o);
        }

        /**
         * Formats a Java object into a JSON String.
         * <p>
         * Expects the Java object to be a java.util.Map, java.util.Iterable,
         * java.lang.String, java.lang.Number, java.lang.Boolean, or null, or
         * nested structure of the above.  All other object types cause a
         * RuntimeException to be thrown.
         *
         * @param o Java object to convert into a JSON String.
         * @return a valid JSON String
         */
        public static String format(boolean pretty, Object o) {
            StringBuilder buf = new StringBuilder(1024);
            prettyPrint(pretty, o, 0, buf);
            String s = buf.toString();
            if (o instanceof Map) {
                return "{" + s + "}";
            } else if (o instanceof Iterable || o instanceof Object[]) {
                return "[" + s + "]";
            } else {
                return s;
            }
        }

        private static Object parseObject(int type, Java2Json p) {
            switch (type) {
                case MAP:
                    Map m = new LinkedHashMap();
                    while (hasNextItem(p, '}')) {
                        String key = nextString(p);
                        nextChar(p, ':');
                        type = nextObject(p);
                        Object obj = parseObject(type, p);
                    /*
                    if (m.containsKey(key)) {
                        throw new RuntimeException("JSON Map Already Contains Key [" + key + "]");
                    }
                     */
                        m.put(key, obj);
                    }
                    return m;

                case LIST:
                    ArrayList l = new ArrayList();
                    while (hasNextItem(p, ']')) {
                        type = nextObject(p);
                        Object obj = parseObject(type, p);
                        l.add(obj);
                    }
                    return l;

                case STRING:
                    return nextString(p);

                case NUMBER:
                    return nextNumber(p);

                case BOOLEAN:
                    return nextBoolean(p);

                case NULL:
                    return nextNull(p);

                default:
                    throw new RuntimeException("invalid type: " + type);
            }
        }

        private static boolean hasNextItem(Java2Json p, char closingBracket) {
            char prev = p.json[p.pos - 1];
            boolean isMap = closingBracket == '}';

            boolean nextCommaExists = nextChar(p, ',', false);
            if (!nextCommaExists) {
                p.pos--;
            }

            char c = p.json[p.pos];
            if (c == closingBracket) {
                p.pos++;
                return false;
            } else if (nextCommaExists) {
                return true;
            } else {
                if (isMap && prev == '{') {
                    return true;
                } else if (!isMap && prev == '[') {
                    return true;
                }
                throw new RuntimeException("expected whitespace or comma or " + closingBracket + " but found: " + c);
            }
        }

        private static int nextObject(Java2Json p) {
            for (int i = p.pos; i < p.json.length; i++) {
                p.pos++;
                char c = p.json[i];

                if (!isWhitespace(c)) {
                    if (c == '"') {
                        p.pos--;
                        return STRING;
                    } else if (c == '{') {
                        return MAP;
                    } else if (c == '[') {
                        return LIST;
                    } else if (c == '-' || (c >= '0' && c <= '9')) {
                        p.pos--;
                        return NUMBER;
                    } else if (c == 'n') {
                        p.pos--;
                        return NULL;
                    } else if (c == 't' || c == 'f') {
                        p.pos--;
                        return BOOLEAN;
                    } else {
                        throw new RuntimeException("Expected whitespace or JSON literal, but got: " + c);
                    }
                }
            }
            return -1; // there is no next object, so we're done
        }

        private static void finalWhitespace(Java2Json p) {
            for (int i = p.pos; i < p.json.length; i++) {
                p.pos++;
                char c = p.json[i];
                if (!isWhitespace(c)) {
                    throw new RuntimeException("Expected whitespace or EOF but got: " + c);
                }
            }
        }

        private static boolean nextChar(Java2Json p, char charToFind) {
            return nextChar(p, charToFind, true);
        }

        private static boolean nextChar(Java2Json p, char charToFind, boolean doThrow) {
            for (int i = p.pos; i < p.json.length; i++) {
                p.pos++;
                char c = p.json[i];

                if (!isWhitespace(c)) {
                    if (c == charToFind) {
                        return true;
                    } else {
                        if (doThrow) {
                            throw new RuntimeException("Expected whitespace or " + charToFind + " but got: " + c);
                        } else {
                            return false;
                        }
                    }
                }
            }
            int offset = Math.max(0, p.pos - 10);
            int count = p.pos - offset;
            throw new RuntimeException("Never found " + charToFind + " context=" + new String(p.json, offset, count));
        }

        private static Object nextNull(Java2Json p) {
            char c = p.json[p.pos++];
            try {
                if (c == 'n') {
                    c = p.json[p.pos++];
                    if (c == 'u') {
                        c = p.json[p.pos++];
                        if (c == 'l') {
                            c = p.json[p.pos++];
                            if (c == 'l') {
                                return null;
                            }
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                throw new RuntimeException("expected null literal but ran of out string to parse");
            }
            throw new RuntimeException("expected null literal but ran into bad character: " + c);
        }

        private static Boolean nextBoolean(Java2Json p) {
            char c = p.json[p.pos++];
            try {
                if (c == 't') {
                    c = p.json[p.pos++];
                    if (c == 'r') {
                        c = p.json[p.pos++];
                        if (c == 'u') {
                            c = p.json[p.pos++];
                            if (c == 'e') {
                                return Boolean.TRUE;
                            }
                        }
                    }
                } else if (c == 'f') {
                    c = p.json[p.pos++];
                    if (c == 'a') {
                        c = p.json[p.pos++];
                        if (c == 'l') {
                            c = p.json[p.pos++];
                            if (c == 's') {
                                c = p.json[p.pos++];
                                if (c == 'e') {
                                    return Boolean.FALSE;
                                }
                            }
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                throw new RuntimeException("expected true/false literal but ran of out string to parse");
            }
            throw new RuntimeException("expected true/false literal but ran into bad character: " + c);
        }

        private static Number nextNumber(Java2Json p) {
            StringBuilder buf = new StringBuilder();
            for (int i = p.pos; i < p.json.length; i++) {
                p.pos++;
                char c = p.json[i];
                if (isWhitespace(c) || c == ',' || c == '}' || c == ']') {
                    p.pos--;
                    break;
                } else if (c == '-' || c == '+' || c == 'e' || c == 'E' || c == '.' || (c >= '0' && c <= '9')) {
                    buf.append(c);
                } else {
                    throw new RuntimeException("expected number but got: " + c);
                }
            }

            String s = buf.toString();
            char char0 = s.length() > 0 ? s.charAt(0) : '_';
            if (char0 == '+') {
                throw new RuntimeException("number literal cannot start with plus: " + s);
            } else if ("-".equals(s)) {
                throw new RuntimeException("number literal cannot be negative sign by itself");
            }
            boolean isNegative = char0 == '-';

            if (isNegative) {
                s = s.substring(1);
            }

            if ("0".equals(s)) {
                return ZERO;
            }

            if (s.startsWith(".")) {
                throw new RuntimeException("number literal cannot start with decimal point: " + s);
            }
            if (!s.startsWith("0.") && !s.startsWith("0e") && !s.startsWith("0E")) {
                if (s.startsWith("0")) {
                    throw new RuntimeException("number literal cannot have leading zero: " + s);
                }
            }

            if (contains(s, ".e") || contains(s, ".E")) {
                throw new RuntimeException("number literal invalid exponential: " + s);
            }

            if (s.endsWith("e") || s.endsWith("E") || s.endsWith("+") || s.endsWith("-") || s.endsWith(".")) {
                throw new RuntimeException("number literal cannot end with [eE+-.] " + s);
            }

            int[] charCounts = charCounts(s);
            int periods = charCounts[0];
            int minuses = charCounts[1];
            int plusses = charCounts[2];
            int eTotal = charCounts[3];
            int plussesAndMinuses = plusses + minuses;

            if (plussesAndMinuses > 0) {
                if (plussesAndMinuses > 1) {
                    throw new RuntimeException("invalid number literal - too many plusses/minuses: " + s);
                } else {
                    boolean isValidPlus = false;
                    boolean isValidMinus = minuses > 0 && (contains(s, "e-") || contains(s, "E-"));
                    if (!isValidMinus) {
                        isValidPlus = plusses > 0 && (contains(s, "e+") || contains(s, "E+"));
                    }
                    if (!isValidPlus && !isValidMinus) {
                        throw new RuntimeException("invalid number literal: " + s);
                    }
                }
            }

            if (periods > 1 || eTotal > 1) {
                throw new RuntimeException("invalid number literal: " + s);
            }

            if (isNegative) {
                s = "-" + s;
            }
            if (periods == 1 || eTotal == 1) {
                return new Double(s);
            } else {
                try {
                    return new Long(s);
                } catch (NumberFormatException nfe) {
                    return new Double(s);
                }
            }
        }


        private static int[] charCounts(String s) {

            // periods, dashes, plusses, lowerOrUpperEs
            int[] counts = {0, 0, 0, 0};

            for (int i = 0; i < s.length(); i++) {
                switch (s.charAt(i)) {
                    case '.':
                        counts[0]++;
                        break;
                    case '-':
                        counts[1]++;
                        break;
                    case '+':
                        counts[2]++;
                        break;
                    case 'E':
                    case 'e':
                        counts[3]++;
                        break;
                    default:
                        break;
                }
            }
            return counts;
        }

        private static String nextString(Java2Json p) {
            int mode = MODE_WHITESPACE;
            StringBuilder buf = new StringBuilder();
            for (int i = p.pos; i < p.json.length; i++) {
                p.pos++;
                char c = p.json[i];
                switch (mode) {
                    case MODE_WHITESPACE:
                        if (c == '"') {
                            mode = MODE_NORMAL;
                        } else if (!isWhitespace(c)) {
                            throw new RuntimeException("json expecting double-quote: " + c);
                        }
                        break;
                    case MODE_NORMAL:
                        if (c == '\\') {
                            mode = MODE_BACKSLASH;
                        } else if (c == '"') {
                            return buf.toString();
                        } else {
                            if (Character.isISOControl(c)) {
                                StringBuilder hex = new StringBuilder(Integer.toHexString(c));
                                if ("7f".equalsIgnoreCase(hex.toString())) {
                                    buf.append(c);
                                } else {
                                    for (int j = hex.length(); j < 4; j++) {
                                        hex.insert(0, "0");
                                    }

                                    if (!tolerant) {
                                        throw new RuntimeException("control characters in string literal must be escaped: \\u" + hex);
                                    } else {
                                        buf.append("\\u").append(hex);
                                    }
                                }
                            } else if (c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') {
                                throw new RuntimeException("json string literal invalid character: " + c);
                            } else {
                                buf.append(c);
                            }
                        }
                        break;
                    case MODE_BACKSLASH:
                        switch (c) {
                            case '/':
                                buf.append('/');
                                break;
                            case 'b':
                                buf.append('\b');
                                break;
                            case 'f':
                                buf.append('\f');
                                break;
                            case 'n':
                                buf.append('\n');
                                break;
                            case 'r':
                                buf.append('\r');
                                break;
                            case 't':
                                buf.append('\t');
                                break;
                            case '"':
                                buf.append('"');
                                break;
                            case '\\':
                                buf.append('\\');
                                break;
                            case 'u':
                                StringBuilder hex = new StringBuilder();
                                for (int j = 0; j < 4; j++) {
                                    try {
                                        char hexChar = p.json[p.pos++];
                                        if (isHex(hexChar)) {
                                            hex.append(hexChar);
                                        } else {
                                            throw new RuntimeException("invalid \\u encoded character (must be hex): " + hexChar);
                                        }
                                    } catch (ArrayIndexOutOfBoundsException aioobe) {
                                        throw new RuntimeException("\\u encoded literal ran out of string to parse");
                                    }
                                }
                                buf.append((char) Integer.parseInt(hex.toString(), 16));
                                i += 4;
                                break;
                            default:
                                throw new RuntimeException("invalid backslash protected character: " + c);
                        }
                        mode = MODE_NORMAL;
                        break;
                }
            }
            throw new RuntimeException("never found literal string terminator \"");
        }

        private static boolean isHex(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private static boolean isWhitespace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r';
        }

        private static boolean contains(String string, String thing) {
            return string.indexOf(thing) >= 0;
        }

        private static StringBuilder prettyPrint(
                final boolean pretty, final Object objParam, final int level, final StringBuilder buf
        ) {
            Iterator it;
            final Object obj;
            if (objParam instanceof Object[]) {
                Object[] objs = (Object[]) objParam;
                obj = Arrays.asList(objs);
            } else {
                obj = objParam;
            }

            if (obj instanceof Map) {
                Map m = (Map) obj;
                it = m.entrySet().iterator();
            } else if (obj instanceof Iterable) {
                Iterable l = (Iterable) obj;
                it = l.iterator();
            } else {
                it = Collections.singleton(obj).iterator();
            }

            while (it.hasNext()) {
                Object o = it.next();
                Object val = o;
                if (val instanceof Object[]) {
                    Object[] objs = (Object[]) val;
                    val = Arrays.asList(objs);
                }

                if (pretty) {
                    buf.append('\n');
                    indent(buf, level);
                }
                if (o instanceof Map.Entry) {
                    Map.Entry me = (Map.Entry) o;
                    Object keyObj = me.getKey();
                    String key;
                    if (keyObj instanceof String) {
                        key = (String) keyObj;
                    } else {
                        key = String.valueOf(keyObj);
                    }
                    buf.append('"');
                    jsonSafe(key, buf);
                    buf.append('"').append(':');
                    val = me.getValue();
                    if (val instanceof Object[]) {
                        Object[] objs = (Object[]) val;
                        val = Arrays.asList(objs);
                    }
                }

                if (val == null || val instanceof Boolean || val instanceof Number) {
                    jsonSafe(val, buf);
                } else if (val instanceof Iterable) {
                    buf.append('[');
                    int lenBefore = buf.length();
                    prettyPrint(pretty, val, level + 1, buf);
                    if (pretty) {
                        int lenAfter = buf.length();
                        if (lenBefore < lenAfter) {
                            buf.append('\n');
                            indent(buf, level);
                        }
                    }
                    buf.append(']');
                } else if (val instanceof Map) {
                    buf.append('{');
                    int lenBefore = buf.length();
                    prettyPrint(pretty, val, level + 1, buf);
                    if (pretty) {
                        int lenAfter = buf.length();
                        if (lenBefore < lenAfter) {
                            buf.append('\n');
                            indent(buf, level);
                        }
                    }
                    buf.append('}');
                } else {
                    buf.append('"');
                    jsonSafe(val, buf);
                    buf.append('"');
                }
                if (it.hasNext()) {
                    buf.append(',');
                }
            }
            return buf;
        }

        private static StringBuilder indent(StringBuilder buf, int level) {
            for (int i = 0; i < level; i++) {
                buf.append("  ");
            }
            return buf;
        }

        private static void jsonSafe(Object o, StringBuilder buf) {
            final String s;
            if (o == null) {
                buf.append("null");
                return;
            } else if (o instanceof Boolean || o instanceof Number) {
                String val = o.toString();
                if ("Infinity".equals(val)) {
                    val = "1e99999";
                } else if ("-Infinity".equals(val)) {
                    val = "-1e99999";
                }
                buf.append(val);
                return;
            } else if (o instanceof Map || o instanceof Iterable) {
                throw new RuntimeException("cannot make Map or Iterable into json string literal: " + o);
            } else if (o instanceof String) {
                s = (String) o;
            } else {
                s = String.valueOf(o);
            }

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\b':
                        buf.append("\\b");
                        break;
                    case '\f':
                        buf.append("\\f");
                        break;
                    case '\n':
                        buf.append("\\n");
                        break;
                    case '\r':
                        buf.append("\\r");
                        break;
                    case '\t':
                        buf.append("\\t");
                        break;
                    case '\\':
                        buf.append("\\\\");
                        break;
                    case '"':
                        buf.append("\\\"");
                        break;
                    default:
                        // We're not interested in control characters U+0000 to U+001F aside from
                        // the allowed ones above.
                        if (Character.isISOControl(c)) {
                            String hex = Integer.toHexString(c);
                            buf.append("\\u");
                            for (int j = hex.length(); j < 4; j++) {
                                buf.append('0');
                            }
                            buf.append(hex);
                        } else {
                            buf.append(c);
                        }
                        break;
                }
            }
        }
    }

    static class Util {

        public static List<String> intoLines(final String... strings) {
            List<String> list = new ArrayList<>();
            if (strings != null) {
                for (final String s : strings) {
                    if (s != null) {
                        StringReader sr = new StringReader(s);
                        BufferedReader br = new BufferedReader(sr);
                        String line;
                        try {
                            while ((line = br.readLine()) != null) {
                                list.add(line);
                            }
                        } catch (IOException ioe) {
                            throw new RuntimeException("impossible - StringReader does not throw IOException - " + ioe, ioe);
                        }
                    }
                }
            }
            return list;
        }

        public static final int SIZE_KEY = 0;
        public static final int LAST_READ_KEY = 1;

        public static String toString(byte[] b) {
            if (b == null) {
                return null;
            }
            if (b.length == 0) {
                return "";
            }

            // Takes a few guesses at the encoding (e.g., UTF-8 vs. UTF-16).
            // The heuristic is based on null and non-null chars in the first 2 positions.
            // (Or first 4 position if a byte-order-mark is present).

            byte b1 = -2;
            byte b2 = -1;
            boolean hasBom = false;
            if (b.length > 2) {
                hasBom = (b[0] == b1 && b[1] == b2) || (b[0] == b2 && b[1] == b1);
            }

            int pos = 0;
            if (hasBom) {
                pos = 2;
            }
            Charset charset = StandardCharsets.UTF_8;
            if (b.length > pos + 2) {
                if (b[pos] != 0) {
                    if (b[pos + 1] == 0) {
                        if (hasBom) {
                            charset = StandardCharsets.UTF_16;
                        } else {
                            charset = StandardCharsets.UTF_16LE;
                        }
                    }
                } else if (b[pos] == 0) {
                    if (b[pos + 1] == 0) {
                        charset = Charset.forName("UTF-32");
                    } else {
                        charset = StandardCharsets.UTF_16BE;
                    }
                }
            }
            return new String(b, charset);
        }

        public static byte[] fileToBytes(File f) {
            return fileToBytes(f, false);
        }

        public static byte[] fileToBytes(File f, final boolean gzipped) {
            InputStream in;
            FileInputStream fin;
            try {
                fin = new FileInputStream(f);
                in = fin;
                if (gzipped) {
                    in = new GZIPInputStream(in);
                }

                if (!gzipped && f.length() <= 32768) {
                    try {
                        byte[] buf = new byte[(int) f.length()];
                        fill(buf, 0, in);
                        return buf;
                    } finally {
                        in.close();
                    }
                } else {
                    try {
                        return streamToBytes(in);
                    } finally {
                        if (gzipped) {
                            fin.close();
                        }
                    }
                }
            } catch (IOException ioe) {
                throw new RuntimeException("Failed to read file [" + f.getName() + "] " + ioe, ioe);
            }
        }

        public static String fileToString(File f) {
            byte[] bytes = fileToBytes(f);
            return toString(bytes);
        }

        public static byte[] streamToBytes(final InputStream in) throws IOException {
            return streamToBytes(in, true);
        }

        public static String streamToString(final InputStream in) throws IOException {
            byte[] bytes = streamToBytes(in, true);
            return toString(bytes);
        }

        public static byte[] streamToBytes(final InputStream in, final boolean doClose) throws IOException {
            return streamToBytes(in, doClose, true);
        }

        public static byte[] streamToBytes(final InputStream in, final boolean doClose, final boolean doResize) throws IOException {
            byte[] buf = new byte[32768];
            try {
                int[] status = fill(buf, 0, in);
                int size = status[SIZE_KEY];
                int lastRead = status[LAST_READ_KEY];
                if (doResize) {
                    while (lastRead != -1) {
                        buf = resizeArray(buf);
                        status = fill(buf, size, in);
                        size = status[SIZE_KEY];
                        lastRead = status[LAST_READ_KEY];
                    }
                }
                if (buf.length != size) {
                    byte[] smallerBuf = new byte[size];
                    System.arraycopy(buf, 0, smallerBuf, 0, size);
                    buf = smallerBuf;
                }
            } finally {
                if (doClose) {
                    in.close();
                }
            }
            return buf;
        }

        public static long stringToFile(String s, File file) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            FileOutputStream fout = null;
            try {
                fout = new FileOutputStream(file);
                fout.write(b);
                fout.flush();
            } finally {
                if (fout != null) {
                    fout.close();
                }
            }
            return file.exists() ? file.length() : -1;
        }

        public static int[] fill(final byte[] buf, final int offset, final InputStream in) throws IOException {
            int read = in.read(buf, offset, buf.length - offset);
            int lastRead = read;
            if (read == -1) {
                read = 0;
            }
            while (lastRead != -1 && read + offset < buf.length) {
                lastRead = in.read(buf, offset + read, buf.length - read - offset);
                if (lastRead != -1) {
                    read += lastRead;
                }
            }
            return new int[]{offset + read, lastRead};
        }

        public static byte[] resizeArray(final byte[] bytes) {
            byte[] biggerBytes = new byte[bytes.length * 2];
            System.arraycopy(bytes, 0, biggerBytes, 0, bytes.length);
            return biggerBytes;
        }

        private static final int REGULAR_CLOSE = 0;
        private static final int CLOSE_AND_COMMIT = 1;

        public static void close(Object o1, Object o2) {
            close(o1, o2, null, null, null);
        }

        public static void close(Object o1, Object o2, Object o3, Object o4, Object o5) {
            close(REGULAR_CLOSE, o1, o2, o3, o4, o5);
        }

        private static void close(int flag, Object... closeArgs) {
            if (closeArgs == null || closeArgs.length == 0) {
                return;
            }

            LinkedList<Throwable> closingProblems = new LinkedList<>();
            for (Object o : closeArgs) {
                if (o == null) {
                    continue;
                }
                try {
                    if (o instanceof HttpURLConnection) {
                        ((HttpURLConnection) o).disconnect();
                    } else if (o instanceof ResultSet) {
                        ((ResultSet) o).close();
                    } else if (o instanceof Statement) {
                        ((Statement) o).close();
                    } else if (o instanceof Connection) {
                        ((Connection) o).close();
                    } else if (o instanceof Reader) {
                        ((Reader) o).close();
                    } else if (o instanceof Writer) {
                        ((Writer) o).close();
                    } else if (o instanceof InputStream) {
                        ((InputStream) o).close();
                    } else if (o instanceof OutputStream) {
                        ((OutputStream) o).close();
                    } else if (o instanceof JarFile) {
                        ((JarFile) o).close();
                    } else if (o instanceof ZipFile) {
                        ((ZipFile) o).close();
                    } else if (o instanceof Process) {
                        ((Process) o).destroy();
                    } else if (o instanceof Closeable) {
                        ((Closeable) o).close();
                    } else {
                        throw new IllegalArgumentException("cannot close: " + o.getClass());
                    }
                } catch (Throwable t) {
                    closingProblems.add(t);
                }
            }

            // Let the close & commit method above handle this instead.
            if (flag == CLOSE_AND_COMMIT && !closingProblems.isEmpty()) {
                throw new CloseFailedException(closingProblems);
            }

            if (!closingProblems.isEmpty()) {
                Throwable t = closingProblems.get(0);
                rethrowIfUnchecked(t);
                throw new RuntimeException("Failed to close something: " + t, t);
            }
        }

        private static class CloseFailedException extends RuntimeException {
            public final LinkedList<Throwable> closingProblems;

            public CloseFailedException(LinkedList<Throwable> closingProblems) {
                this.closingProblems = closingProblems;
            }
        }


        public static void rethrowIfUnchecked(Throwable t) {
            if (t instanceof Error) {
                throw (Error) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
        }
    }
}
