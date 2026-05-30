package Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/*
 * Classe utilitária para leitura e escrita de frames no protocolo TCP.
 * Cada frame começa com um byte de tipo, seguido por um inteiro de 4 bytes indicando o tamanho do payload, e então o payload em si.
 * O tipo do frame determina como o payload deve ser interpretado (texto UTF-8 para comandos e mensagens de chat, ou bytes brutos para dados de arquivo).
*/

public class Frame {
 
    // Escreve um frame de texto (FRAME_MSG) no stream:
    public static void writeMsgFrame(DataOutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.writeByte(Protocol.FRAME_MSG); // Tipo do frame
        out.writeInt(payload.length);      // Tamanho do payload em bytes
        out.write(payload);                // Payload UTF-8
        out.flush();
    }
 
    // Escreve um frame de dados binários (FRAME_FILE_DATA) no stream:
    public static void writeBinaryFrame(DataOutputStream out, byte[] data, int offset, int length) throws IOException {
        out.writeByte(Protocol.FRAME_FILE_DATA);
        out.writeInt(length);
        out.write(data, offset, length);
        out.flush();
    }
 
    // Escreve um frame vazio para sinalizar o fim de uma transferência de arquivo:
    public static void writeFileEndFrame(DataOutputStream out) throws IOException {
        out.writeByte(Protocol.FRAME_FILE_END);
        out.writeInt(0); // Payload vazio — o tipo é a informação
        out.flush();
    }
 
    // Lê o próximo byte do stream e retorna o tipo do frame, sem consumir o payload:
    public static byte readFrameType(DataInputStream in) throws IOException {
        return in.readByte();
    }
 
    // Lê o payload de um frame de texto já identificado (o tipo já foi consumido):
    public static String readMsgPayload(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] payload = new byte[length];
        in.readFully(payload); // Garante leitura completa, mesmo em rede lenta
        return new String(payload, StandardCharsets.UTF_8);
    }
 
    // Lê e descarta o payload de um frame desconhecido, mantendo o stream sincronizado:
    public static void skipPayload(DataInputStream in) throws IOException {
        int length = in.readInt();
        int remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining); // skip() pode pular menos que o pedido em redes lentas
            if (skipped <= 0) break;
            remaining -= (int) skipped;
        }
    }
}