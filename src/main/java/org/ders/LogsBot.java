package org.ders;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class LogsBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String botToken;
    private final long allowedChatId;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;

    private static final Map<String, String> LOCAL_ENV = new HashMap<>();

    static {
        // Пробуем загрузить переменные из файла .env в корне проекта
        File envFile = new File(".env");
        if (envFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    // убираем кавычки, если есть
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    LOCAL_ENV.put(key, value);
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static void main(String[] args) throws Exception{
        startHealthServer();
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(new LogsBot());
    }

    private static void startHealthServer() throws IOException {
        // Render Web Service ожидает, что процесс будет слушать порт из переменной PORT.
        // Поднимаем простой HTTP сервер для health-check.
        int port = 10000;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException ignored) {
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        System.out.println("Health server listening on port " + port);
    }

    public LogsBot(){
        this.botToken = getenvOrThrow("LOGIBOT_TOKEN");
        this.botUsername = "logibot";
        this.allowedChatId = Long.parseLong(getenvOrThrow("LOGIBOT_CHAT_ID"));

        this.dbUrl = getenvOrThrow("LOGIBOT_DB_URL");
        this.dbUser = getenvOrThrow("LOGIBOT_DB_USER");
        this.dbPass = getenvOrThrow("LOGIBOT_DB_PASS");

        System.out.println("Bot initialized. allowedChatId=" + allowedChatId);
    }


    private static String getenvOrThrow(String name){
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            v = LOCAL_ENV.get(name);
        }
        if (v== null || v.isEmpty()){
            throw new IllegalStateException("Env var " + name + " is not set");
        }
        return v;
    }

    @Override
    public String getBotUsername(){
        return botUsername;
    }

    @Override
    public String getBotToken(){
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }
        long chatId = update.getMessage().getChatId();
        // Логируем только chatId, чтобы не было каши из-за кодировки консоли
        System.out.println("UPDATE chatId=" + chatId);

        // Фильтр: работаем только в одном чате
        if (chatId != allowedChatId) {
            return;
        }
        String text = update.getMessage().getText().trim();
        if (text.isEmpty()) {
            return;
        }

        try{
            if (text.startsWith("!логи") || text.startsWith("логи")){
                handleLogsCommand(chatId, text);
            } else if (text.startsWith("!ban")) {
                handleBanCommand(chatId, text);
            }

            } catch (Exception e){
            e.printStackTrace();
            sendText(chatId, "Ошибка: " + e.getMessage());
        }
    }

    private void handleLogsCommand(long chatId, String text) throws Exception{
        String[] parts = text.split("\\s+", 5);
        if(parts.length < 5){
            sendText(chatId, "Юз: !логи <лимит> <сервер/%> <ник/%> <запрос/%>");
            return;
        }

        int limit;
        try{
            limit = Integer.parseInt(parts[1]);

        } catch (NumberFormatException e){
            sendText(chatId, "Ты ваще идиот? лимит - число");
            return;
        }

        if(limit <= 0 || limit > 60000){
            sendText(chatId, "Лимит от 1 до 60000 вкл.");
            return;
        }

        String server = parts[2];
        String nick = parts[3];
        String query = parts[4];

        sendText(chatId, "Ищу логи: лимит= " + limit + ", сервер= " + server + ", ник= " + nick + ", запрос= " + query);

        String queryLike = query.equals("%") ? "%" : wrapLike(query);
        String serverLike = server;
        String nickLike = nick;

        StringBuilder buf = new StringBuilder();
        // Шапка файла (даже если строк нет)
        buf.append("nick\tip\tserver\tdate\tmessage\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT dt, server, nick, ip, message " +
                             "FROM logs " +
                             "WHERE (? = '%' OR server LIKE ?) " +
                             "  AND (? = '%' OR nick LIKE ?) " +
                             "  AND (? = '%' OR message LIKE ?) " +
                             "ORDER BY dt DESC " +
                             "LIMIT ?"
             )
        ){
            ps.setString(1, serverLike);
            ps.setString(2, serverLike);
            ps.setString(3, nickLike);
            ps.setString(4, nickLike);
            ps.setString(5, queryLike);
            ps.setString(6, queryLike);
            ps.setInt(7, limit);

            try(ResultSet rs = ps.executeQuery()){
                while(rs.next()){
                    Timestamp ts = rs.getTimestamp("dt");
                    String dtStr = ts.toLocalDateTime().format(fmt);
                    String line = rs.getString("nick") + "\t" +
                            rs.getString("ip") + "\t" +
                            rs.getString("server") + "\t" +
                            dtStr + "\t" +
                            rs.getString("message") + "\n";
                    buf.append(line);
                }
            }
        }

        byte[] bytes = buf.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        SendDocument doc = new SendDocument();
        doc.setChatId(Long.toString(chatId));
        InputFile file = new InputFile(in, "logs.txt");
        doc.setDocument(file);
        doc.setCaption(
                "Лимит: " + limit + "\n" +
                "Сервер: " + prettyFilter(server) + "\n" +
                "Ник: " + prettyFilter(nick) + "\n" +
                "Запрос: " + prettyFilter(query)
        );
        try {
            execute(doc);
        } finally {
            in.close();
        }
    }

    private static String prettyFilter(String v) {
        if (v == null || v.isEmpty()) {
            return "% (Любой)";
        }
        if ("%".equals(v)) {
            return "% (Любой)";
        }
        return v;
    }

    private static String wrapLike(String s) {
        if (!s.startsWith("%")) s = "%" + s;
        if (!s.endsWith("%")) s = s + "%";
        return s;
    }
    private void handleBanCommand(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendText(chatId, "Использование: !ban <nick1> <nick2> ...");
            return;
        }
        StringBuilder info = new StringBuilder("Отправляю баны по никам:\n");
        for (int i = 1; i < parts.length; i++) {
            String nick = parts[i].trim();
            if (nick.isEmpty()) continue;
            // TODO: здесь будет отправка команды на сервер, например через RCON:
            // rconSend("/hsp " + nick);
            info.append("- ").append(nick).append("\n");
        }
        sendText(chatId, info.toString());
    }
    private void sendText(long chatId, String text) {
        SendMessage msg = new SendMessage(Long.toString(chatId), text);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
