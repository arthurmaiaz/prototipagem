package br.unifor.cct.biometria.nitgen;

import com.nitgen.SDK.AndroidBSP.NBioBSPJNI;

import java.io.IOException;
import java.util.Arrays;

import br.unifor.cct.biometria.BiometricDatabase;

/**
 * Cadastro e remoção da digital de um professor com o leitor Hamster DX.
 *
 * O cadastro faz duas leituras do mesmo dedo, com o dedo saindo do leitor
 * entre elas, para serem duas colocações diferentes. Só salva se as duas
 * conferirem entre si, e guarda as duas como
 * amostras, o que deixa a busca mais tolerante à posição do dedo. Também
 * recusa uma digital que já está cadastrada para outro professor.
 *
 * Salva no BiometricDatabase (cifrado), na primeira posição livre do array
 * (ou na posição que o professor já ocupava).
 * Todos os callbacks chegam na thread principal.
 */
public class CadastroDigitalNitgen {

    public interface Callback {
        /** Instrução para mostrar ao professor durante o cadastro. */
        void onEtapa(String instrucao);

        void onConcluido(String mensagem);

        void onErro(String mensagem);
    }

    private final NitgenSdk sdk;
    private final BiometricDatabase banco;

    public CadastroDigitalNitgen(NitgenSdk sdk, BiometricDatabase banco) {
        this.sdk = sdk;
        this.banco = banco;
    }

    public void cadastrar(int professorId, Callback callback) {
        sdk.executar(() -> {
            int qualidade = NitgenSdk.QUALIDADE_ACEITA;
            etapa(callback, "Coloque o dedo no leitor (leitura 1 de 2).");
            String primeira = sdk.capturar(NBioBSPJNI.FIR_PURPOSE.ENROLL, qualidade);

            etapa(callback, "Tire o dedo do leitor.");
            sdk.aguardarDedoSair();

            etapa(callback, "Coloque o MESMO dedo de novo (leitura 2 de 2).");
            String segunda = sdk.capturar(NBioBSPJNI.FIR_PURPOSE.ENROLL, qualidade);

            if (!sdk.mesmaDigital(primeira, segunda)) {
                throw new NitgenException("As duas leituras não conferem. Tente de novo, com o mesmo dedo.");
            }
            // Percorre o array: a digital não pode ser de outro professor.
            for (String amostra : Arrays.asList(primeira, segunda)) {
                for (int dono : banco.identificar(salva -> sdk.mesmaDigital(amostra, salva))) {
                    if (dono != professorId) {
                        throw new NitgenException("Essa digital já está cadastrada para o professor " + dono + ".");
                    }
                }
            }

            boolean jaTinha = banco.buscar(professorId) != null;
            int posicao;
            try {
                posicao = banco.salvar(professorId, Arrays.asList(primeira, segunda));
            } catch (IllegalStateException e) {
                throw new NitgenException(e.getMessage());
            } catch (IOException e) {
                throw new NitgenException("Falha ao salvar a digital no aparelho.");
            }
            return "Digital do professor " + professorId
                    + (jaTinha ? " atualizada" : " cadastrada")
                    + " na posição " + posicao + " (2 amostras).";
        }, resultado(callback));
    }

    public void remover(int professorId, Callback callback) {
        sdk.executar(() -> {
            int posicao = banco.posicaoDo(professorId);
            try {
                banco.remover(professorId);
            } catch (IOException e) {
                throw new NitgenException("Falha ao apagar a digital do aparelho.");
            }
            return posicao >= 0
                    ? "Digital do professor " + professorId + " removida (posição " + posicao + " livre)."
                    : "O professor " + professorId + " não tinha digital cadastrada.";
        }, resultado(callback));
    }

    private void etapa(Callback callback, String instrucao) {
        sdk.naPrincipal(() -> callback.onEtapa(instrucao));
    }

    private static NitgenSdk.Resultado<String> resultado(Callback callback) {
        return new NitgenSdk.Resultado<String>() {
            @Override
            public void onSucesso(String mensagem) {
                callback.onConcluido(mensagem);
            }

            @Override
            public void onErro(String mensagem) {
                callback.onErro(mensagem);
            }
        };
    }
}
