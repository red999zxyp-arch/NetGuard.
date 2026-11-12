# NetGuard AI

Aplicativo Android nativo (Java) para observar a conectividade e manter um histórico local.

## Funcionalidades desta versão (0.7.0)

- Identifica o transporte ativo: Ethernet, Wi-Fi ou dados móveis.
- Busca hosts que respondem na sub-rede local por ARP ou portas TCP selecionadas, em tarefa de segundo plano; não equivale à lista completa de clientes do roteador.
- Apresenta estatísticas das até 100 amostras válidas mais recentes: mínimo, mediana, média e máximo de latência TCP.
- Mede o tempo de abertura de conexão TCP com dois destinos independentes: `1.1.1.1:443` (Cloudflare) e `8.8.8.8:443` (Google); se um falhar, o outro ainda pode indicar conectividade externa.
- Descobre o gateway padrão dinamicamente pelas rotas da rede ativa e testa suas portas TCP 80 e 443, sem assumir IP fixo nem que porta fechada signifique roteador offline.
- Mede o tempo de resolução DNS de `example.com` via resolvedor configurado no Android; é tempo de consulta/resolução, não ping nem teste completo de saúde do DNS.
- Exibe detalhes disponíveis da rede ativa: transporte, validação de internet, gateway, DNS e endereços locais.
- Abre o painel local usando o gateway descoberto dinamicamente; a disponibilidade da interface web depende do roteador e do navegador.
- Monitoramento manual com intervalos de 5, 10, 30 ou 60 segundos, enquanto a tela permanece aberta.
- Histórico local limitado a 2.000 amostras.
- Gráfico das últimas 80 amostras válidas.
- Estimativa suavizada da variação absoluta entre medições TCP consecutivas.
- Resumo de falhas TCP, comparação de destinos e diagnóstico textual cauteloso.
- Notificações locais de possível interrupção após dois ciclos consecutivos sem resposta nos dois destinos e de recuperação, enquanto o monitoramento está ativo e a permissão de notificações foi concedida.
- Preferências de intervalo e perfil Geral/Jogos persistidas localmente.
- Alertas avançados configuráveis para limiar de latência TCP (100–500 ms) e variação TCP estimada (15–100 ms), com aviso por episódio e rearme após recuperação.
- Preferências de limites e ativação de alertas persistidas localmente.
- Exportação CSV pelo seletor de documentos do Android.
- Exclusão manual do histórico.

## Precisão das medições

O tempo para estabelecer TCP com `1.1.1.1:443` **não é ping ICMP** e inclui o estabelecimento da conexão. A variação exibida é uma estimativa baseada na diferença absoluta entre amostras TCP consecutivas; não equivale a jitter UDP/ICMP medido no caminho do jogo.

A taxa de falhas é a proporção de tentativas TCP que não completaram, não perda de pacotes IP. A tentativa à porta 80 do roteador pode falhar porque a porta está fechada, filtrada ou a interface web usa outro protocolo; isso não prova que o roteador esteja offline.

## Limitações

- O monitoramento e os alertas dependem de o app permanecer aberto e ativo; não há serviço em primeiro plano, monitoramento 24/7 ou alertas remotos.
- O teste no celular não mede o ping real do Rocket League em outro dispositivo.
- A descoberta local não é uma lista oficial de clientes do roteador: dispositivos silenciosos, isolados ou com portas fechadas podem não aparecer; MAC pode ficar indisponível no Android.
- Não há integração com API administrativa do roteador, controle de QoS ou alteração automática da rede. O app apenas verifica conectividade TCP e abre o painel web; não conhece as credenciais nem altera o firmware/configuração. O modelo/firmware Vivo pode bloquear ou não expor esses recursos; não há overclock físico via app. O atalho do painel usa o gateway padrão descoberto na rede ativa e abre `http://<gateway>` no navegador.
- Não há envio de medições para nuvem nem alertas remotos. As notificações são locais e só podem ser emitidas durante a execução do monitoramento.
- A IA ainda não é um modelo treinado: os textos diagnósticos são regras locais conservadoras.
- Os limites de alerta são configuráveis, mas dependem das medições do próprio celular; não representam a latência real do dispositivo que executa o Rocket League.

## Compilar

GitHub Actions usa Java 17, Android SDK 36 e Gradle 8.14.4. Abra a aba **Actions**, selecione o workflow e baixe o artefato `NetGuardAI-debug-apk` após uma execução concluída com sucesso.

## Privacidade e segurança

Histórico mantido em armazenamento privado do aplicativo, sem sincronização externa. O app solicita apenas permissões de internet e estado da rede. Não solicita senha do roteador e não modifica configurações.
