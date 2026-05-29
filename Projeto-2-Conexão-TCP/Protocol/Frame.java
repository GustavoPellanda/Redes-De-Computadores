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

    // Escreve um frame de texto (CMD ou CHAT_MSG) no stream:
    public static void writeTextFrame(DataOutputStream out, byte frameType, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.writeByte(frameType); // Tipo do frame
        out.writeInt(payload.length); // Tamanho do payload em bytes
        out.write(payload); // Payload UTF-8
        out.flush();
    }

    // Escreve um frame de dados binários (FILE_DATA) no stream:
    public static void writeBinaryFrame(DataOutputStream out, byte frameType, byte[] data, int offset, int length) throws IOException {
        out.writeByte(frameType);
        out.writeInt(length);
        out.write(data, offset, length);
        out.flush();
    }

    // Lê o próximo byte do stream e retorna o tipo do frame, sem consumir o payload:
    public static byte readFrameType(DataInputStream in) throws IOException {
        return in.readByte();
    }

    // Lê o payload de um frame de texto já identificado (o tipo já foi consumido):
    public static String readTextPayload(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] payload = new byte[length];
        in.readFully(payload); // Garante leitura completa, mesmo em rede lenta
        return new String(payload, StandardCharsets.UTF_8);
    }

    // Lê e descarta o payload de um frame desconhecido, mantendo o stream sincronizado:
    public static void skipPayload(DataInputStream in) throws IOException {
        int length = in.readInt();
        in.skipBytes(length);
    }

    // Escreve um frame vazio (sem payload) para sinalizar eventos como o fim de um arquivo:
    public static void writeEmptyFrame(DataOutputStream out, byte frameType) throws IOException {
        out.writeByte(frameType);
        out.writeInt(0); // payload vazio
        out.flush();
    }
}