# Servidor TCP de Transferência de Arquivos com Sistema de Chat

## Sumário

1. [Visão Geral do Projeto](#1-visão-geral-do-projeto)
2. [Estrutura de Pacotes e Classes](#2-estrutura-de-pacotes-e-classes)
   - 2.1 [Pacote `Protocol`](#21-pacote-protocol)
   - 2.2 [Pacote `Server`](#22-pacote-server)
3. [Modelo de Concorrência](#3-modelo-de-concorrência)
   - 3.1 [Thread por cliente](#31-thread-por-cliente)
   - 3.2 [Thread de broadcast periódico](#32-thread-de-broadcast-periódico)
4. [Gerenciamento da Lista de Clientes Ativos](#4-gerenciamento-da-lista-de-clientes-ativos)
   - 4.1 [Escolha da estrutura: `CopyOnWriteArrayList`](#41-escolha-da-estrutura-copyonwritearraylist)
   - 4.2 [Operações sobre a lista e ausência de condições de corrida](#42-operações-sobre-a-lista-e-ausência-de-condições-de-corrida)
5. [Buffers de Entrada e Saída no `ClientHandler`](#5-buffers-de-entrada-e-saída-no-clienthandler)
6. [O Protocolo de Framing sobre TCP](#6-o-protocolo-de-framing-sobre-tcp)
   - 6.1 [Por que TCP sozinho não basta](#61-por-que-tcp-sozinho-não-basta)
   - 6.2 [Estrutura do frame](#62-estrutura-do-frame)
   - 6.3 [Tipos de frame definidos](#63-tipos-de-frame-definidos)
   - 6.4 [A classe `Frame` como camada de serialização](#64-a-classe-frame-como-camada-de-serialização)
7. [Sistema de Mensagens e a Classe `ServerChat`](#7-sistema-de-mensagens-e-a-classe-serverchat)
   - 7.1 [Design stateless](#71-design-stateless)
   - 7.2 [Classificação das mensagens](#72-classificação-das-mensagens)
   - 7.3 [Fluxo de uma mensagem do servidor ao cliente](#73-fluxo-de-uma-mensagem-do-servidor-ao-cliente)
8. [Interpretação de Comandos no Servidor](#8-interpretação-de-comandos-no-servidor)
   - 8.1 [Comandos reconhecidos](#81-comandos-reconhecidos)
   - 8.2 [Respostas e mensagens enviadas pelo servidor](#82-respostas-e-mensagens-enviadas-pelo-servidor)
9. [Segurança: Prevenção de Path Traversal](#9-segurança-prevenção-de-path-traversal)
10. [Verificação de Integridade via SHA-256](#10-verificação-de-integridade-via-sha-256)

---

## 1. Visão Geral do Projeto

Este projeto implementa um servidor TCP em Java capaz de atender múltiplos clientes simultaneamente, oferecendo dois serviços integrados num único canal de comunicação: transferência de arquivos sob demanda e um sistema de mensagens bidirecional.

O servidor opera em escuta contínua numa porta fixa, delegando cada conexão aceita a uma thread dedicada. Um módulo de chat independente permite ao servidor enviar mensagens assíncronas — notificações de eventos, alertas periódicos e respostas a comandos específicos — para todos os clientes conectados ou para clientes individuais.

A integração desses dois serviços num mesmo stream TCP exigiu a criação de um protocolo de aplicação próprio, baseado em frames tipados, capaz de diferenciar dados de arquivo de mensagens de texto dentro do fluxo contínuo de bytes que o TCP fornece.

---

## 2. Estrutura de Pacotes e Classes

O projeto está organizado em dois pacotes com responsabilidades distintas:

```
projeto/
├── Protocol/
│   ├── Protocol.java   — Constantes e definições do protocolo
│   └── Frame.java      — Serialização e desserialização de frames
└── Server/
    ├── Server.java         — Ponto de entrada, loop de aceitação e agendamento
    ├── ClientHandler.java  — Thread dedicada a cada cliente
    └── ServerChat.java     — Lógica de geração e envio de mensagens
```

### 2.1 Pacote `Protocol`

**`Protocol.java`**

Centraliza todas as constantes compartilhadas entre servidor e cliente. Funciona como o contrato formal do protocolo de comunicação: qualquer valor que precise ser reconhecido pelos dois lados — porta de conexão, tamanho de chunk, nomes de comandos, prefixos de mensagens, tipos de frame — está definido aqui. Isso garante que uma alteração num valor se propague automaticamente para todos os pontos que o utilizam, sem risco de inconsistência entre as partes.

**`Frame.java`**

Implementa a camada de framing do protocolo. Oferece métodos estáticos para escrever e ler frames no stream TCP, abstraindo os detalhes de serialização: cálculo de tamanho de payload, escolha de codificação de caracteres (UTF-8), escrita atômica de tipo + tamanho + conteúdo. Nenhuma outra classe precisa conhecer o formato binário de um frame; toda essa lógica reside aqui.

### 2.2 Pacote `Server`

**`Server.java`**

É o ponto de entrada da aplicação. Responsabilidades:

- Abrir o `ServerSocket` e mantê-lo em escuta na porta definida em `Protocol.PORT`.
- Executar o loop principal de aceitação, que bloqueia em `serverSocket.accept()` até um novo cliente se conectar.
- Para cada conexão aceita, instanciar um `ClientHandler`, registrá-lo na lista global e iniciar sua thread.
- Instanciar o `ServerChat` e mantê-lo como referência compartilhada entre todos os handlers.
- Iniciar a thread de broadcast periódico.

**`ClientHandler.java`**

Implementa `Runnable`, sendo projetada para execução em thread própria. Cada instância representa a sessão de um único cliente e é inteiramente responsável por aquele canal de comunicação. Responsabilidades:

- Manter os streams de entrada (`DataInputStream`) e saída (`DataOutputStream`) sobre o socket, inicializados no construtor.
- Executar o loop de leitura de frames, extraindo o texto de cada `FRAME_MSG` recebido e passando-o para `dispatch()`.
- Interpretar o texto recebido e executar a ação correspondente: processar transferências de arquivo (`GET`), delegar comandos de informação (`HELP`, `STATUS`) ao `ServerChat`, ou encerrar a sessão (`EXIT`).
- Ao encerrar a sessão, remover-se da lista global e notificar os demais clientes via `ServerChat`.

**`ServerChat.java`**

Encapsula toda a lógica relacionada a mensagens. Não gerencia conexões nem mantém estado de sessão — opera exclusivamente sobre referências de streams e sobre a lista de clientes ativos recebida do servidor. Responsabilidades:

- Construir e enviar mensagens de resposta a comandos específicos (`HELP`, `STATUS`).
- Realizar broadcasts para todos os clientes conectados.
- Gerar as mensagens periódicas e os eventos de conexão/desconexão.
- Calcular e formatar o tempo de atividade do servidor para o comando `STATUS`.

---

## 3. Modelo de Concorrência

O servidor adota o modelo *thread-per-connection*: cada cliente conectado é atendido por uma thread Java dedicada, criada no momento da aceitação da conexão e encerrada quando a sessão termina.

### 3.1 Thread por cliente

```
Thread principal (Server)
│
├── serverSocket.accept()  ← bloqueia aqui
│
└── Nova conexão aceita
    ├── new ClientHandler(socket, activeClients, serverChat)
    ├── activeClients.add(handler)
    └── new Thread(handler).start()  → Thread do cliente C1
                                           │
                                           └── loop: readFrame → dispatch → ...
```

O loop de `accept()` retorna imediatamente ao estado de espera após delegar o cliente, permitindo aceitar a próxima conexão sem qualquer atraso. As threads dos clientes operam em paralelo e de forma totalmente independente entre si: o bloqueio de uma thread esperando dados do seu cliente não afeta as demais.

Todas as threads de clientes são marcadas como *daemon* (`thread.setDaemon(true)`). Threads daemon são encerradas automaticamente pela JVM quando não há mais threads não-daemon em execução, evitando que conexões abertas impeçam o servidor de terminar.

### 3.2 Thread de broadcast periódico

Uma segunda thread daemon é iniciada junto ao servidor para disparar notificações periódicas. Ela dorme por um intervalo fixo (60 segundos) e, ao acordar, invoca o método de broadcast adequado no `ServerChat`. Por ser daemon, ela também encerra junto com a JVM sem necessidade de gerenciamento explícito.

```java
Thread periodicBroadcast = new Thread(() -> {
    int tickCount = 0;
    while (running) {
        Thread.sleep(60_000);
        if (tickCount % 2 == 0) serverChat.broadcastPeriodicAlive();
        else                    serverChat.broadcastPeriodicSecurity();
        tickCount++;
    }
});
periodicBroadcast.setDaemon(true);
periodicBroadcast.start();
```

O campo `running` é declarado como `volatile` para garantir que a atualização feita por `stop()` — potencialmente em outra thread — seja imediatamente visível à thread do broadcast, sem risco de ela continuar em loop por ter o valor cacheado em registrador pela JVM.

---

## 4. Gerenciamento da Lista de Clientes Ativos

### 4.1 Escolha da estrutura: `CopyOnWriteArrayList`

A lista de clientes ativos é declarada como `CopyOnWriteArrayList<ClientHandler>`:

```java
private final List<ClientHandler> activeClients = new CopyOnWriteArrayList<>();
```

Essa estrutura da biblioteca padrão do Java foi projetada especificamente para cenários onde iterações são frequentes e modificações (inserções e remoções) são menos frequentes — exatamente o perfil deste servidor, onde broadcasts iteram a lista constantemente enquanto conexões e desconexões são operações pontuais.

O mecanismo interno da `CopyOnWriteArrayList` é o seguinte: toda operação de escrita (add, remove) cria uma **cópia completa do array interno**, realiza a modificação sobre essa cópia e a substitui atomicamente pela referência original. Iterações, por sua vez, operam sobre um *snapshot* imutável capturado no momento em que o iterador foi criado.

### 4.2 Operações sobre a lista e ausência de condições de corrida

Três pontos do código alteram ou percorrem a lista simultaneamente:

| Operação | Onde ocorre | Thread executora |
|---|---|---|
| `activeClients.add(handler)` | `Server.start()` | Thread principal |
| `activeClients.remove(this)` | `ClientHandler.close()` | Thread do cliente que encerrou |
| Iteração em `broadcast()` | `ServerChat.broadcast()` | Thread do cliente ou thread periódica |

Sem sincronização, uma thread removendo um cliente enquanto outra itera a lista causaria `ConcurrentModificationException` com um `ArrayList` comum. Com `CopyOnWriteArrayList`, isso não ocorre: a iteração opera sobre o snapshot capturado no início do loop, e qualquer modificação na lista durante a iteração afeta apenas o array subjacente, sem interferir no snapshot em uso.

Uma consequência intencional desse comportamento: se um cliente desconecta exatamente durante um broadcast em andamento, ele ainda pode receber aquela mensagem específica (pois estava no snapshot), mas não receberá as seguintes. Isso é semanticamente correto — a mensagem foi enviada enquanto o cliente ainda estava oficialmente ativo.

A ordem das operações em `close()` é deliberada: o handler é removido da lista *antes* de o broadcast de desconexão ser disparado. Isso garante que o servidor não tentará escrever no socket já fechado do cliente que está saindo.

```java
activeClients.remove(this);              // Remove primeiro
serverChat.broadcastClientDisconnected(); // Depois notifica os que restaram
```

---

## 5. Buffers de Entrada e Saída no `ClientHandler`

Os streams do socket são envolvidos com `DataInputStream` e `DataOutputStream`:

```java
this.in  = new DataInputStream(socket.getInputStream());
this.out = new DataOutputStream(socket.getOutputStream());
```

Esses streams são inicializados no construtor de `ClientHandler`, não em `run()`. Isso é necessário porque `ServerChat.broadcast()` acessa `getOutputStream()` logo após a conexão ser aceita — potencialmente antes de a nova thread ser escalonada pela JVM para executar `run()`. Inicializar no construtor garante que `out` nunca seja `null` quando um broadcast tenta usá-lo.

Para a transferência de arquivos, o `ClientHandler` lê e transmite o conteúdo em *chunks* de tamanho fixo definido por `Protocol.CHUNK_SIZE` (8 KB):

```java
byte[] buffer = new byte[Protocol.CHUNK_SIZE];
int bytesRead;
while ((bytesRead = fis.read(buffer)) != -1) {
    Frame.writeBinaryFrame(out, buffer, 0, bytesRead);
}
```

Esse padrão tem duas consequências importantes para o gerenciamento de memória:

**Limite de uso de RAM independente do tamanho do arquivo.** Independentemente de o arquivo ter 1 KB ou 10 GB, o servidor aloca apenas 8 KB por cliente para a transferência. Sem esse padrão, seria necessário carregar o arquivo inteiro em memória antes de transmiti-lo, tornando o servidor inviável para arquivos grandes.

**Limite de memória proporcional ao número de clientes, não ao tamanho dos arquivos.** Com *N* clientes transferindo arquivos simultaneamente, o consumo de memória de buffers é *N* × 8 KB — um valor previsível e controlado.

O mesmo padrão é aplicado no cálculo do hash SHA-256: o `MessageDigest` é alimentado bloco a bloco com os mesmos 8 KB, calculando o hash incremental sem precisar do arquivo completo na RAM. Ambos os `FileInputStream` — em `sendFile` e em `calculateSHA256` — são abertos com `try-with-resources`, garantindo que o descritor de arquivo seja fechado mesmo em caso de exceção durante a transferência.

---

## 6. O Protocolo de Framing sobre TCP

### 6.1 Por que TCP sozinho não basta

O TCP é um protocolo de **fluxo de bytes** (*byte stream*). Ele garante entrega ordenada e sem perdas, mas não preserva fronteiras entre transmissões. Quando o servidor executa dois envios consecutivos:

```java
out.write("OK".getBytes());
out.write(hashBytes);
```

...esses dois envios podem ser entregues ao cliente como um único segmento TCP, como dois segmentos separados, ou fragmentados em qualquer ponto dependendo do estado da rede. O cliente não consegue inferir onde termina `"OK"` e começa o hash apenas observando os bytes.

O problema se torna crítico quando dois tipos de dado qualitativamente diferentes precisam coexistir no mesmo stream: mensagens de texto e chunks binários de arquivo. Sem um mecanismo que identifique o tipo de dado antes de seu conteúdo, o cliente não sabe se os próximos bytes são texto a exibir ou dados binários a gravar em disco.

### 6.2 Estrutura do frame

A solução adotada é um esquema de *framing* com três campos:

```
┌────────────┬──────────────────────────┬────────────────────────────────┐
│  Tipo      │  Tamanho do payload      │  Payload                       │
│  (1 byte)  │  (4 bytes, big-endian)   │  (N bytes, UTF-8 ou binário)   │
└────────────┴──────────────────────────┴────────────────────────────────┘
```

**Tipo (1 byte):** identifica como o payload deve ser interpretado. O leitor lê este byte primeiro e decide qual método de desserialização aplicar ao restante.

**Tamanho (4 bytes, inteiro com sinal em big-endian):** indica quantos bytes compõem o payload. Com 4 bytes, o limite teórico é ~2 GB por frame, suficiente para qualquer caso de uso prático.

**Payload (N bytes):** o conteúdo real. Para frames de texto, os bytes são uma string codificada em UTF-8. Para frames de dados de arquivo, são bytes brutos do arquivo.

Esse esquema é uma instância do padrão *length-prefix framing* (ou *TLV — Type-Length-Value*), amplamente utilizado em protocolos de aplicação como HTTP/2, Protocol Buffers e TLS.

### 6.3 Tipos de frame definidos

O protocolo define apenas três tipos de frame, suficientes para separar texto de dados binários:

| Constante | Valor | Uso |
|---|---|---|
| `FRAME_MSG` | `0x01` | Toda comunicação textual: mensagens do cliente, respostas e notificações do servidor |
| `FRAME_FILE_DATA` | `0x02` | Chunk de dados binários de um arquivo em transferência |
| `FRAME_FILE_END` | `0x03` | Frame vazio sinalizando o fim de uma transferência de arquivo |

A simplificação para um único tipo de frame textual (`FRAME_MSG`) é intencional. Do ponto de vista do protocolo de transporte, não importa se o texto é um comando, uma resposta ou uma notificação — todos são texto UTF-8 e todos são tratados da mesma forma pelo leitor. A distinção semântica é responsabilidade da camada de aplicação, não do framing.

### 6.4 A classe `Frame` como camada de serialização

A classe `Frame` isola completamente o restante do código dos detalhes do protocolo binário. Os métodos `writeMsgFrame`, `writeBinaryFrame` e `writeFileEndFrame` garantem que tipo, tamanho e payload sejam sempre escritos na ordem correta. Os métodos `readFrameType`, `readMsgPayload` e `skipPayload` fazem o caminho inverso.

O método `readFully` é usado intencionalmente na leitura do payload:

```java
in.readFully(payload); // Garante leitura completa, mesmo em rede lenta
```

A alternativa, `in.read(payload)`, pode retornar com menos bytes do que o solicitado caso o sistema operacional ainda não tenha recebido o restante do segmento TCP — comportamento correto do ponto de vista da API, mas que produziria um payload truncado. `readFully` bloqueia até que todos os `length` bytes estejam disponíveis, garantindo a integridade do frame.

O método `skipPayload` usa um loop explícito em vez de `skipBytes`, pois `skipBytes` não garante que todos os bytes serão descartados — ele pode retornar antecipadamente em redes lentas, deixando o stream desincronizado:

```java
public static void skipPayload(DataInputStream in) throws IOException {
    int remaining = in.readInt();
    while (remaining > 0) {
        long skipped = in.skip(remaining);
        if (skipped <= 0) break;
        remaining -= (int) skipped;
    }
}
```

---

## 7. Sistema de Mensagens e a Classe `ServerChat`

### 7.1 Design stateless

`ServerChat` é projetada como uma classe *stateless* em relação às sessões individuais dos clientes. Ela não armazena filas de mensagens por cliente, não mantém estado de leitura nem rastreia quais mensagens já foram entregues a quem. O único estado interno que ela mantém é:

- `activeClients`: uma referência à lista global de handlers (não é estado próprio — é uma referência compartilhada com o servidor).
- `startTimeMillis`: o instante de criação, usado exclusivamente para calcular o uptime no comando `STATUS`.

Isso tem uma consequência direta: **uma mensagem broadcast é enviada exatamente uma vez, para todos os clientes presentes no momento do envio**. Clientes que conectam após um broadcast não recebem mensagens retroativas, e clientes que desconectam durante um broadcast simplesmente falham silenciosamente na escrita (a exceção é capturada em `sendMessage`). Não há reenvio, persistência ou confirmação de entrega.

Essa escolha é adequada para mensagens de notificação — eventos de conexão, alertas periódicos — onde a entrega melhor-esforço é semanticamente correta.

### 7.2 Classificação das mensagens

As mensagens do sistema se dividem em quatro categorias funcionais. Todas trafegam como `FRAME_MSG` — a categoria é indicada pelo prefixo do texto, não pelo tipo do frame.

**Respostas a comandos do cliente (unicast, sob demanda)**

Enviadas para um único cliente em resposta direta a um texto reconhecido como comando.

| Comando recebido | Resposta enviada |
|---|---|
| `HELP` | Texto de ajuda descrevendo todos os comandos disponíveis |
| `STATUS` | Número de clientes conectados e tempo de atividade formatado |
| `GET <arquivo>` | `[ARQUIVO] OK <hash> <tamanho>` seguido dos chunks binários, ou `[ERRO] <descrição>` |

**Broadcasts engatilhados por eventos (multicast, assíncrono)**

Enviados automaticamente quando um evento relevante ocorre no servidor, independentemente de qualquer requisição de cliente.

| Evento | Mensagem enviada |
|---|---|
| Novo cliente conectado | `[EVENT] Novo cliente conectado ao servidor.` |
| Cliente desconectado | `[EVENT] Cliente desconectado.` |

**Broadcasts periódicos (multicast, agendado)**

Enviados em intervalos regulares pela thread de broadcast, sem gatilho externo. Alternam entre duas mensagens a cada ciclo:

- `[ADMIN NOTICE] Servidor está ativo e funcionando normalmente.`
- `[ADMIN NOTICE] Todas as conexões estão criptograficamente verificadas via SHA-256.`

**Mensagens de erro (unicast, geradas pelo servidor)**

Enviadas diretamente ao cliente que provocou o erro — tentativa de path traversal, arquivo inexistente, comando `GET` sem nome de arquivo.

- `[ERRO] Acesso negado: caminho inválido.`
- `[ERRO] Arquivo não encontrado: <nome>`
- `[ERRO] Uso correto: GET <nome_do_arquivo>`

### 7.3 Fluxo de uma mensagem do servidor ao cliente

Toda mensagem percorre o seguinte caminho antes de chegar ao cliente:

```
ServerChat.broadcast(texto)
    └── para cada handler em activeClients:
            ServerChat.sendMessage(handler.getOutputStream(), texto)
                └── Frame.writeMsgFrame(out, texto)
                        ├── out.writeByte(0x01)          ← FRAME_MSG
                        ├── out.writeInt(bytes.length)   ← tamanho do payload
                        └── out.write(bytes UTF-8)       ← payload
```

O cliente, ao ler do stream, encontra `0x01` como tipo de frame, sabe que o payload é texto, lê o tamanho e então a string — sem qualquer ambiguidade com dados binários de arquivo.

---

## 8. Interpretação de Comandos no Servidor

O cliente envia texto livre. O servidor, ao receber um `FRAME_MSG`, passa o texto para `dispatch()` em `ClientHandler`, que verifica se o texto corresponde a algum comando conhecido.

### 8.1 Comandos reconhecidos

| Texto enviado pelo cliente | Ação executada pelo servidor |
|---|---|
| `GET <nome_do_arquivo>` | Valida o caminho, calcula hash e envia o arquivo em chunks |
| `EXIT` | Encerra a sessão graciosamente |
| `HELP` | Envia o texto de ajuda via `ServerChat` |
| `STATUS` | Envia métricas do servidor via `ServerChat` |
| Qualquer outro texto | Registrado no log do servidor como mensagem não reconhecida |

A comparação ignora maiúsculas/minúsculas (`equalsIgnoreCase`). O argumento do `GET` é extraído subtraindo o prefixo `"GET "` do texto recebido, sem exigir que o cliente formate o comando de nenhuma forma especial além do espaço separador.

### 8.2 Respostas e mensagens enviadas pelo servidor

**Em resposta a `GET` (sucesso):**
```
FRAME_MSG("[ARQUIVO] OK <hash-sha256> <tamanho-em-bytes>")
FRAME_FILE_DATA(<chunk 0>)
FRAME_FILE_DATA(<chunk 1>)
...
FRAME_FILE_END
```

**Em resposta a `GET` (falha):**
```
FRAME_MSG("[ERRO] <mensagem descritiva>")
```

**Em resposta a `HELP` ou `STATUS`:**
```
FRAME_MSG("<texto da resposta>")
```

**Mensagens assíncronas de broadcast:**
```
FRAME_MSG("[ADMIN NOTICE] <texto>" | "[EVENT] <texto>")
```

---

## 9. Segurança: Prevenção de Path Traversal

O servidor valida todo caminho de arquivo solicitado antes de abri-lo. O ataque de *path traversal* consiste em enviar nomes de arquivo contendo sequências como `../../etc/passwd` para navegar fora do diretório autorizado.

A defesa implementada resolve ambos os caminhos para suas formas canônicas e verifica a relação de prefixo:

```java
File rootDir       = new File(Protocol.FILES_DIR).getCanonicalFile();
File requestedFile = new File(rootDir, filename).getCanonicalFile();

if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
    // Acesso negado
}
```

`getCanonicalFile()` resolve symlinks, remove componentes `.` e `..` e retorna o caminho absoluto real no sistema de arquivos. Se o caminho resultante não começa com o diretório raiz, o acesso é negado antes que qualquer operação de I/O seja realizada.

---

## 10. Verificação de Integridade via SHA-256

Antes de transmitir um arquivo, o servidor calcula seu hash SHA-256 completo e o envia ao cliente como parte da mensagem de metadados que precede os chunks. O cliente pode, após receber todos os bytes, calcular o hash do arquivo recebido e compará-lo com o valor recebido.

O cálculo é feito de forma incremental, alimentando o `MessageDigest` bloco a bloco com os mesmos 8 KB usados na transferência:

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");
try (FileInputStream fis = new FileInputStream(file)) {
    while ((bytesRead = fis.read(buffer)) != -1) {
        digest.update(buffer, 0, bytesRead);
    }
}
byte[] hashBytes = digest.digest();
```

Isso garante que o consumo de memória durante o cálculo do hash seja idêntico ao da transferência: constante e limitado a 8 KB, independentemente do tamanho do arquivo.

A verificação via SHA-256 detecta tanto corrupção acidental de dados em trânsito quanto modificações no arquivo entre o momento do cálculo e o recebimento pelo cliente — o que o aviso periódico `[ADMIN NOTICE] Todas as conexões estão criptograficamente verificadas via SHA-256` anuncia aos clientes conectados.
