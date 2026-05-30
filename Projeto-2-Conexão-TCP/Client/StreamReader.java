package Client;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.SynchronousQueue;

import Protocol.Frame;
import Protocol.Protocol;

/*
* Thread dedicada exclusivamente à leitura do stream de dados do servidor.
* Responsável por interpretar os frames recebidos, extrair mensagens de texto
* e chunks de arquivo, e sinalizar para a thread principal (Client) quando
* um arquivo estiver pronto para verificação ou quando uma mensagem de texto
* chegar.
*
* O loop de leitura é controlado por uma flag "running" que pode ser setada
* para false por Client quando a conexão for encerrada, garantindo que a thread
* termine normalmente em vez de lançar IOException ao tentar ler de um stream fechado. 
*/

public class StreamReader implements Runnable {

    private final DataInputStream in;                     // Stream de entrada — lido exclusivamente por esta thread
    private final SynchronousQueue<String> responseQueue; // Canal de entrega de sinais para Client
    private volatile boolean running;                     // Flag de controle do loop — escrita por Client, lida aqui
    private FileOutputStream activeFileOutput = null;     // Stream de saída para o arquivo sendo recebido, ou null se nenhum em andamento
    private long expectedSize = 0;                        // Tamanho total esperado do arquivo em bytes
    private long receivedBytes = 0;                       // Bytes acumulados até o momento
    private int chunkIndex = 0;                           // Índice do chunk atual

    public StreamReader(DataInputStream in, SynchronousQueue<String> responseQueue) {
        this.in= in;
        this.responseQueue = responseQueue;
        this.running= true;
    }

    // Sinaliza ao loop que a sessão foi encerrada — chamado por Client ao fechar a conexão:
    public void stop() {
        running = false;
    }

    // Loop principal de leitura — interpreta frames e delega a handlers específicos:
    @Override
    public void run() {
        try {
            while (running) {
                byte frameType = Frame.readFrameType(in);

                switch (frameType) {
                    case Protocol.FRAME_MSG:
                        String text = Frame.readMsgPayload(in);
                        handleMsg(text);
                        break;
                    case Protocol.FRAME_FILE_DATA:
                        handleFileChunk();
                        break;
                    case Protocol.FRAME_FILE_END:
                        Frame.skipPayload(in); // Payload vazio — apenas consome o campo de tamanho
                        finalizeFile();
                        break;
                    default:
                        // Frame desconhecido — descarta o payload para manter o stream sincronizado:
                        Frame.skipPayload(in);
                        System.out.println("\n[Cliente] Frame desconhecido ignorado: tipo=" + frameType);
                        break;
                }
            }
        } catch (IOException e) {
            if (running) {
                // Conexão encerrada inesperadamente enquanto ainda deveria estar ativa:
                System.out.println("\n[Cliente] Conexão encerrada pelo servidor: " + e.getMessage());
            }
            // Sinaliza para Client que a conexão foi encerrada, para que ela possa reagir (ex.: sair do prompt):
            try {
                responseQueue.put("__CONNECTION_CLOSED__");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Interpreta uma mensagem de texto recebida — pode ser resposta a um comando ou mensagem de chat:
    private void handleMsg(String text) throws IOException {
        if (text.startsWith(Protocol.PREFIX_FILE)) {
            // Extrai o conteúdo após o prefixo "[ARQUIVO] ":
            String content = text.substring(Protocol.PREFIX_FILE.length()).trim();

            if (content.startsWith("OK ")) {
                // Formato esperado: "OK <hash-sha256> <tamanho-em-bytes>"
                String[] parts = content.substring(3).split(" ", 2);
                String hash = parts[0];
                long fileSize = Long.parseLong(parts[1]);

                // Sinaliza para Client os metadados para que ele possa preparar o download:
                try {
                    responseQueue.put("__FILE_META__ " + hash + " " + fileSize);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                // Resposta de erro do GET — repassa para Client via fila:
                try {
                    responseQueue.put("__FILE_ERROR__ " + content);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            // Mensagem comum (broadcast, HELP, STATUS, erro de outro tipo) — imprime no console:
            System.out.println("\n" + text);
            System.out.print("> "); // Reimprime o prompt
        }
    }

    // Lê um chunk de arquivo do stream e o grava no arquivo de saída:
    private void handleFileChunk() throws IOException {
        int length = in.readInt();  // Tamanho do payload deste chunk
        byte[] buffer = new byte[length];
        in.readFully(buffer);       // Garante leitura completa, mesmo em rede lenta

        if (activeFileOutput != null) {
            activeFileOutput.write(buffer);
            receivedBytes += length;
            System.out.println("[Cliente] chunk " + chunkIndex + " recebido (" + length + " bytes) | "
                    + receivedBytes + "/" + expectedSize + " bytes");
            chunkIndex++;
        }
    }

    // Fecha o arquivo, notifica Client via responseQueue e limpa o estado da transferência:
    private void finalizeFile() throws IOException {
        if (activeFileOutput == null) return;

        activeFileOutput.close();
        System.out.println("[Cliente] Transferência concluída — todos os chunks recebidos.");

        // Sinaliza para Client que o arquivo está pronto para verificação de integridade:
        try {
            responseQueue.put("__FILE_DONE__");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Limpa o estado para a próxima transferência:
        activeFileOutput = null;
        expectedSize = 0;
        receivedBytes = 0;
        chunkIndex = 0;
    }

    // Abre o arquivo de saída e registra os metadados:
    public void beginFileTransfer(String filename, long size) throws IOException {
        expectedSize = size;
        receivedBytes = 0;
        chunkIndex = 0;
        activeFileOutput = new FileOutputStream("received_" + filename);
    }
}
