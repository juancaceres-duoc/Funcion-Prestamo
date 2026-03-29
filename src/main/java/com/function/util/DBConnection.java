package com.function.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DBConnection {

    private static final String TEMP_WALLET_DIR =
            System.getProperty("java.io.tmpdir") + "/wallet";

    private static boolean walletInitialized = false;

    public static Connection getConnection() throws Exception {

        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new RuntimeException("Faltan variables de entorno");
        }

        initWallet();

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);

        System.setProperty("oracle.net.tns_admin", TEMP_WALLET_DIR);
        props.setProperty("oracle.net.tns_admin", TEMP_WALLET_DIR);

        Class.forName("oracle.jdbc.OracleDriver");

        return DriverManager.getConnection(url, props);
    }

    private static void initWallet() throws Exception {

        if (walletInitialized) return;

        File walletDir = new File(TEMP_WALLET_DIR);

        if (!walletDir.exists()) {
            walletDir.mkdirs();

            try (InputStream is = DBConnection.class
                    .getClassLoader()
                    .getResourceAsStream("wallet.zip")) {

                if (is == null) {
                    throw new RuntimeException("wallet.zip no encontrado en resources");
                }

                unzip(is, walletDir);
            }
        }

        walletInitialized = true;
    }

    private static void unzip(InputStream zipStream, File targetDir) throws Exception {

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {

            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                
                String fileName = entry.getName().replace("wallet/", "");

                if (fileName.isEmpty()) continue;

                File newFile = new File(targetDir, fileName);

                if (entry.isDirectory()) {
                    newFile.mkdirs();
                    continue;
                }

                new File(newFile.getParent()).mkdirs();

                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
    }
}