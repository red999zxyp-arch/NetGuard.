# NetGuard AI

Aplicativo Android nativo para observar a conectividade, registrar medições e exportar histórico.

## Estado atual

- Painel Android nativo em Java.
- Identificação do transporte ativo (Ethernet, Wi-Fi ou dados móveis).
- Medição de tempo de conexão TCP com `1.1.1.1:443`.
- Tentativa de conexão TCP com `192.168.15.1:80`.
- Histórico local limitado a 1.500 amostras.
- Gráfico local de latência TCP.
- Exportação CSV pelo seletor de documentos do Android.

## Precisão e limitações

O tempo TCP não é ping ICMP. A variação entre amostras é uma estimativa de jitter da conexão TCP. Falhas de conexão TCP não representam perda de pacotes IP. A porta 80 do roteador pode estar fechada ou indisponível, sem que isso signifique que o roteador esteja offline.

Nesta versão, o monitoramento ocorre enquanto a tela do aplicativo permanece ativa. O Android pode suspender ou encerrar processos em segundo plano; portanto, esta versão não promete monitoramento 24/7.

## Compilar

O workflow do GitHub Actions compila o projeto com Java 17, Android SDK 36 e Gradle 8.14.4. O APK de depuração é publicado como artefato da execução bem-sucedida.

## Segurança

O aplicativo não altera configurações do roteador, não solicita senha administrativa e não envia o histórico para servidores externos.
