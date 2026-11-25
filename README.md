# LunaNX Android

O LunaNX nasceu no Nintendo Switch. Este fork leva o cliente para o Android, com a biblioteca do Amazon Luna, busca e reprodução em tela cheia. Dá pra jogar com controle Bluetooth, USB ou com os botões na tela.

O port é experimental e pede Android 9 ou superior em um aparelho ARM64, além de uma conta com acesso ao Amazon Luna. A disponibilidade dos jogos depende da região e do plano da conta.

## Como jogar

1. Abra o app, escolha o país da sua conta Amazon e gere um código. Confirme o código na página da Amazon indicada pelo app.
2. Escolha um jogo na biblioteca. Com um controle físico conectado, os botões do player saem da tela.
3. Dê dois toques na imagem ou aperte **Start + Select** pra abrir o menu. Ali você pode ajustar o enquadramento, mostrar as métricas ou sair da partida.

Em **Conta → Idioma dos jogos**, dá pra pedir português do Brasil no próximo lançamento. A preferência é experimental. O idioma disponível e a forma de selecionar áudio ou legendas variam por jogo.

## Como compilar

No Linux, instale JDK 17 ou superior, Python 3.12 ou superior, CMake e Ninja. No Android SDK, instale a plataforma 35, o Build-Tools e um NDK. Aponte `ANDROID_SDK_ROOT` e `ANDROID_NDK_HOME` para as instalações. Na raiz do repositório, rode o comando abaixo.

```sh
JOBS=4 sh android/build-apk.sh
```

O APK sai em `android/build/LunaNX-Android.apk`. O script cria uma chave de desenvolvimento na primeira build. Para atualizar uma instalação existente, use a mesma chave que assinou o APK instalado.

## Se o vídeo engasgar

Abra **Métricas** no player. Se abrir uma issue, inclua a versão do app, o modelo do aparelho e os números de FPS recebido e decodificado, descartes RTP e resets. Diga também se estava espelhando pra uma TV. Sessões da Amazon e chaves de assinatura devem ficar no seu aparelho.

## Origem e licença

Este fork modifica o LunaNX 3.1 para Android. O port inclui interface nativa, vídeo H.264 via MediaCodec assíncrono, áudio via AAudio e suporte a controles Android. As alterações do port foram publicadas em 25 de novembro de 2026.

O projeto original é do **KazaR‑RooT**. O LunaNX Android é um projeto comunitário independente da Amazon e da Nintendo. Consulte o [LunaNX original](https://github.com/kazar-root/LunaNX), a [GPL‑3.0](LICENSE) e os [créditos das dependências](THIRD_PARTY_NOTICES.md).
