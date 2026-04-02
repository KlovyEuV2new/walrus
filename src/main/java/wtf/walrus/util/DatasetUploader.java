package wtf.walrus.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DatasetUploader {

    public static File zipDataDir(File dataDir) throws IOException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File zipFile = new File(dataDir.getParentFile(), "dataset_" + timestamp + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            File[] files = dataDir.listFiles();
            if (files == null) throw new IOException("Data directory is empty or does not exist");

            for (File file : files) {
                if (file.isFile() && file.getName().trim().endsWith(".csv")) {
                    ZipEntry entry = new ZipEntry(file.getName());
                    zos.putNextEntry(entry);
                    Files.copy(file.toPath(), zos);
                    zos.closeEntry();
                }
            }
        }
        return zipFile;
    }

    public static String uploadToSite(File file) throws IOException {
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();

        URL url = new URL("https://tmpfiles.org/api/v1/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(600000);

        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {

            os.write((
                    "--" + boundary + "\r\n" +
                            "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8));

            Files.copy(file.toPath(), os);

            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder response = getStringBuilder(conn);

        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();

        if (!json.has("data")) {
            throw new IOException("Invalid response: " + response);
        }

        String pageUrl = json.getAsJsonObject("data").get("url").getAsString();
        return pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/");
    }

    private static @NonNull StringBuilder getStringBuilder(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();

        InputStream is = (status == 200)
                ? conn.getInputStream()
                : conn.getErrorStream();

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }

        if (status != 200) {
            throw new IOException("HTTP " + status + ": " + response);
        }
        return response;
    }
}