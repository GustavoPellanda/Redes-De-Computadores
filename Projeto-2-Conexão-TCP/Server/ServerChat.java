package Server;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import Protocol.Protocol;
import Protocol.Frame;

/*
 * Encapsula toda a lógica de chat do servidor:
 *   - Envio de mensagens diretas a um único cliente
 *   - Broadcast para todos os clientes conectados
 *   - Geração das respostas programadas (HELP, STATUS, notificações periódicas, eventos)
 *
 * Não mantém estado de conexão — recebe a lista de handlers do servidor e delega
 * a escrita de bytes ao Frame. Assim, ClientHandler e Server permanecem focados
 * em suas responsabilidades originais.
 */

public class ServerChat {

    private final List<ClientHandler> activeClients; // Referência à lista global de handlers
    private final long startTimeMillis; // Instante em que o servidor foi iniciado

    public ServerChat(List<ClientHandler> activeClients) {
        this.activeClients = activeClients;
        this.startTimeMillis = System.currentTimeMillis();
    }

    // ----- Envio de mensagens a clientes individuais -----

    // Envia uma mensagem de chat para um único cliente:
    public void sendMessage(DataOutputStream out, String message) {
        try {
            Frame.writeTextFrame(out, Protocol.FRAME_CHAT_MSG, message);
        } catch (IOException e) {
            System.err.println("[Chat] Falha ao enviar mensagem: " + e.getMessage());
        }
    }

    // Responde ao comando HELP com instruções de uso do serviço:
    public void handleHelp(DataOutputStream out) {
        String helpText =
            "=== Ajuda do servidor de arquivos ===\n" +
            "Comandos disponíveis:\n" +
            "  GET <nome_do_arquivo>  — Baixa um arquivo do servidor\n" +
            "  HELP                  — Exibe esta mensagem de ajuda\n" +
            "  STATUS                — Exibe o status atual do servidor\n" +
            "  EXIT                  — Encerra a conexão\n" +
            "=====================================";
        sendMessage(out, helpText);
    }

    // Responde ao comando STATUS com métricas atuais do servidor:
    public void handleStatus(DataOutputStream out) {
        long elapsedMillis = System.currentTimeMillis() - startTimeMillis;
        String uptime = formatUptime(elapsedMillis);

        String statusText =
            Protocol.PREFIX_STATUS + "Clientes conectados: " + activeClients.size() + "\n" +
            Protocol.PREFIX_STATUS + "Tempo de atividade do servidor: " + uptime;

        sendMessage(out, statusText);
    }

    // ----- Broadcast para todos os clientes -----

    // Envia uma mensagem para todos os clientes ativos simultaneamente:
    public void broadcast(String message) {
        System.out.println("[Chat] Broadcast → " + message);

        // Itera sobre um snapshot da lista de handlers ativos, enviando a mensagem a cada um:
        for (ClientHandler handler : activeClients) {
            sendMessage(handler.getOutputStream(), message);
        }
    }

    // Notifica todos os clientes que um novo cliente se conectou:
    public void broadcastClientConnected() {
        broadcast(Protocol.PREFIX_EVENT + "Novo cliente conectado ao servidor.");
    }

    // Notifica todos os clientes que um cliente se desconectou:
    public void broadcastClientDisconnected() {
        broadcast(Protocol.PREFIX_EVENT + "Cliente desconectado.");
    }

    // Envia o aviso periódico de que o servidor está operando normalmente:
    public void broadcastPeriodicAlive() {
        broadcast(Protocol.PREFIX_ADMIN + "Servidor está ativo e funcionando normalmente.");
    }

    // Envia o aviso periódico sobre integridade criptográfica das transferências:
    public void broadcastPeriodicSecurity() {
        broadcast(Protocol.PREFIX_ADMIN + "Todas as conexões estão criptograficamente verificadas via SHA-256.");
    }

    // Converte milissegundos em uma string "HHh:MMm:SSs":
    private String formatUptime(long millis) {
        long totalSeconds = millis / 1000;
        long hours   = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02dh:%02dm:%02ds", hours, minutes, seconds);
    }
}