# NetGuard AI

Aplicativo Android nativo (Java) para observar a conectividade e manter um histórico local.

## Funcionalidades desta versão (0.2.0)

- Identifica o transporte ativo: Ethernet, Wi-Fi ou dados móveis.
- Mede o tempo de abertura de conexão TCP com `1.1.1.1:443`.
- Tenta abrir conexão TCP com `192.168.15.1:80` como verificação auxiliar da rede local.
- Monitoramento manual com intervalos de 5, 10, 30 ou 60 segundos, enquanto a tela permanece aberta.
- Histórico local limitado a 2.000 amostras.
- Gráfico das últimas 80 amostras válidas.
- Estimativa suavizada da variação absoluta entre medições TCP consecutivas.
- Resumo de falhas de conexão TCP e diagnóstico textual cauteloso.
- Exportação CSV pelo seletor de documentos do Android.
- Exclusão manual do histórico.

## Precisão das medições

O tempo para estabelecer TCP com `1.1.1.1:443` **não é ping ICMP** e inclui o estabelecimento da conexão. A variação exibida é uma estimativa baseada na diferença absoluta entre amostras TCP consecutivas; não equivale a jitter UDP/ICMP medido no caminho do jogo.

A taxa de falhas é a proporção de tentativas TCP que não completaram, não perda de pacotes IP. A tentativa à porta 80 do roteador pode falhar porque a porta está fechada, filtrada ou a interface web usa outro protocolo; isso não prova que o roteador esteja offline.

## Limitações

- O monitoramento desta versão só funciona enquanto o app está aberto; não promete operação 24/7.
- O teste no celular não mede o ping real do Rocket League em outro dispositivo.
- Não há integração com API administrativa do roteador, controle de QoS ou alteração automática da rede.
- Não há envio de medições para nuvem nem alertas remotos.
- A IA ainda não é um modelo treinado: os textos diagnósticos são regras locais conservadoras.

## Compilar

GitHub Actions usa Java 17, Android SDK 36 e Gradle 8.14.4. Abra a aba **Actions**, selecione o workflow e baixe o artefato `NetGuardAI-debug-apk` após uma execução concluída com sucesso.

## Privacidade e segurança

Histórico mantido em armazenamento privado do aplicativo, sem sincronização externa. O app solicita apenas permissões de internet e estado da rede. Não solicita senha do roteador e não modifica configurações.
