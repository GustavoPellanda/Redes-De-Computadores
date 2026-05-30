package Server;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import Protocol.Frame;
import Protocol.Protocol;

/*
 * Thread dedicada ao atendimento de um único cliente TCP.
 * Criada pelo servidor a cada nova conexão aceita, ela encapsula toda a lógica
 * de comunicação com aquele cliente: lê mensagens de texto, interpreta comandos,
 * processa requisições de arquivo e encerra a sessão quando o cliente envia EXIT
 * ou a conexão cai.
 *
 * Toda comunicação textual — tanto do cliente para o servidor quanto do servidor
 * para o cliente — trafega em FRAME_MSG. O servidor é quem interpreta o texto
 * recebido e decide se é um comando ou uma mensagem de chat comum.
 *
 * Ao terminar, remove a si mesma da lista global de handlers ativos mantida pelo servidor.
*/

public class ClientHandler implements Runnable {

    private final Socket socket;                     // Socket TCP da conexão com este cliente
    private final List<ClientHandler> activeClients; // Referência à lista global de handlers
    private final DataInputStream in;                // Stream de leitura de dados primitivos do cliente
    private final DataOutputStream out;              // Stream de escrita de dados primitivos para o cliente
    private final String clientId;                   // Identificador textual do cliente (IP:porta), usado nos logs
    private final ServerChat serverChat;             // Referência ao módulo de chat, para gerar respostas

    public ClientHandler(Socket socket, List<ClientHandler> activeClients, ServerChat serverChat) throws IOException {
        this.socket = socket;
        this.activeClients = activeClients;
        this.serverChat = serverChat;
        this.clientId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        this.in  = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    @Override
    public void run() {
        System.out.println("[Servidor] Cliente conectado: " + clientId);

        try {
            // Loop principal: lê frames do cliente até receber EXIT ou a conexão fechar:
            while (true) {
                byte frameType = Frame.readFrameType(in); // Lê o tipo do próximo frame antes de interpretar o payload

                if (frameType == Protocol.FRAME_MSG) {
                    String text = Frame.readMsgPayload(in); // Lê o texto enviado pelo cliente
                    dispatch(text);

                } else {
                    // Frame de tipo desconhecido — descarta o payload para manter o stream sincronizado:
                    Frame.skipPayload(in);
                    System.out.println("[Servidor] Frame de tipo desconhecido recebido de " + clientId);
                }
            }

        } catch (IOException e) { // Conexão encerrada abruptamente pelo cliente (ex.: processo morto)
            System.out.println("[Servidor] Conexão encerrada com " + clientId + ": " + e.getMessage());

        } finally {
            close();
        }
    }

    // Interpreta o texto recebido do cliente e executa a ação correspondente:
    private void dispatch(String text) throws IOException {
        String trimmed = text.trim();

        if (trimmed.equalsIgnoreCase(Protocol.CMD_EXIT)) {
            System.out.println("[Servidor] Cliente " + clientId + " solicitou encerramento.");
            throw new IOException("EXIT solicitado pelo cliente"); // Encerra o loop via catch de IOException

        } else if (trimmed.toUpperCase().startsWith(Protocol.CMD_GET)) {
            // Extrai o nome do arquivo que segue após "GET " (com espaço):
            String filename = trimmed.substring(Protocol.CMD_GET.length()).trim();
            handleGet(filename);

        } else if (trimmed.equalsIgnoreCase(Protocol.CMD_HELP)) {
            serverChat.handleHelp(out);

        } else if (trimmed.equalsIgnoreCase(Protocol.CMD_STATUS)) {
            serverChat.handleStatus(out);

        } else {
            System.out.println("[Servidor] Mensagem não reconhecida de " + clientId + ": " + trimmed);
        }
    }

    // Processa um comando GET:
    private void handleGet(String filename) throws IOException {
        System.out.println("[Servidor] " + clientId + " solicitou arquivo: " + filename);

        if (filename.isEmpty()) {
            serverChat.sendMessage(out, Protocol.PREFIX_ERROR + "Uso correto: GET <nome_do_arquivo>");
            return;
        }

        // Resolve o caminho do arquivo solicitado em relação ao diretório raiz, e verifica se ele realmente está dentro do diretório permitido:
        File rootDir = new File(Protocol.FILES_DIR).getCanonicalFile();
        File requestedFile = new File(rootDir, filename).getCanonicalFile();

        // Verifica se o caminho resolvido ainda está dentro do diretório raiz:
        if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
            System.out.println("[Servidor] Tentativa de path traversal bloqueada de " + clientId + ": " + filename);
            serverChat.sendMessage(out, Protocol.PREFIX_ERROR + "Acesso negado: caminho inválido.");
            return;
        } // *Sem essa checagem, um cliente poderia enviar "../../etc/passwd" e acessar arquivos arbitrários

        if (!requestedFile.exists() || !requestedFile.isFile()) {
            System.out.println("[Servidor] Arquivo não encontrado para " + clientId + ": " + filename);
            serverChat.sendMessage(out, Protocol.PREFIX_ERROR + "Arquivo não encontrado: " + filename);
            return;
        }

        // Calcula o hash antes de enviar, para o cliente verificar a integridade após receber:
        String hash = calculateSHA256(requestedFile);

        // Envia os metadados do arquivo como uma mensagem de texto antes dos bytes binários:
        serverChat.sendMessage(out, Protocol.PREFIX_FILE + "OK " + hash + " " + requestedFile.length());

        System.out.println("[Servidor] Enviando " + requestedFile.getName() +" (" + requestedFile.length() + " bytes) para " + clientId);

        sendFile(requestedFile);

        System.out.println("[Servidor] Transferência de " + requestedFile.getName() + " concluída para " + clientId);
    }

    // Lê o arquivo em chunks e escreve cada chunk no stream de saída:
    private void sendFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[Protocol.CHUNK_SIZE];
            int bytesRead;
            int chunkIndex = 0;

            // Lê o arquivo em pedaços de CHUNK_SIZE bytes e envia cada pedaço como um frame binário:
            while ((bytesRead = fis.read(buffer)) != -1) {
                Frame.writeBinaryFrame(out, buffer, 0, bytesRead);
                System.out.println("[Servidor] chunk " + chunkIndex + " enviado (" + bytesRead + " bytes) para " + clientId);
                chunkIndex++;
            } // *Evita carregar o arquivo inteiro na memória
        }

        Frame.writeFileEndFrame(out); // Sentinela: indica ao cliente que todos os chunks foram enviados
    }

    // Calcula o hash SHA-256 do arquivo lendo-o em blocos de CHUNK_SIZE bytes:
    private String calculateSHA256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(Protocol.HASH_ALGORITHM);

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[Protocol.CHUNK_SIZE];
                int bytesRead;

                // Alimenta o digest com cada bloco lido, sem precisar ter o arquivo inteiro na RAM:
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            // Converte o array de bytes do digest para uma string hexadecimal legível:
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Algoritmo " + Protocol.HASH_ALGORITHM + " não disponível", e);
        }
    }

    // Fecha o socket e remove este handler da lista global de clientes ativos:
    private void close() {
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("[Servidor] Erro ao fechar socket de " + clientId + ": " + e.getMessage());
        }

        // Remove primeiro, depois notifica — evita tentar escrever no socket já fechado deste cliente:
        activeClients.remove(this);
        serverChat.broadcastClientDisconnected();

        System.out.println("[Servidor] Cliente desconectado: " + clientId + " | Ativos: " + activeClients.size());
    }

    // Expõe o stream de saída para que ServerChat possa fazer broadcast:
    public DataOutputStream getOutputStream() {
        return out;
    }
}