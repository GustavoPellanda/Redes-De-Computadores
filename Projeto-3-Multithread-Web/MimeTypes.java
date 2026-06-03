/*
 * Classe utilitária para mapear extensões de arquivos a seus respectivos Content-Types.
*/

public class MimeTypes {

    // Retorna o Content-Type correspondente à extensão do arquivo:
    public static String getContentType(String filename) {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css"))  return "text/css; charset=utf-8";
        if (lower.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (lower.endsWith(".txt"))  return "text/plain; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".xml"))  return "application/xml; charset=utf-8";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".ico"))  return "image/x-icon";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".mp4"))  return "video/mp4";

        return "application/octet-stream"; // Tipo genérico para arquivos binários desconhecidos
    }
}
