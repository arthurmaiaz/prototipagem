package br.unifor.cct.biometria;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class BiometricDatabaseTest {

    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private File arquivo;
    private Cifra cifra;

    @Before
    public void setUp() throws Exception {
        arquivo = new File(pasta.getRoot(), "digitais.bin");
        KeyGenerator gerador = KeyGenerator.getInstance("AES");
        gerador.init(256);
        cifra = new CifraAesGcm(gerador.generateKey());
    }

    private static final String ANA = "ana@unifor.br";
    private static final String BRUNO = "bruno@unifor.br";
    private static final String CARLA = "carla@unifor.br";
    private static final String DIEGO = "diego@unifor.br";

    /** 2 arrays de 3 posições, para os testes de distribuição. */
    private BiometricDatabase pequeno() {
        return new BiometricDatabase(arquivo, cifra, 2, 3);
    }

    @Test
    public void padraoSaoDoisArraysDeTrezentos() {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(2, db.arrays());
        assertEquals(300, db.tamanhoArray());
        assertEquals(600, db.capacidade());
    }

    @Test
    public void salvaERecarregaDeOutraInstancia() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(ANA, Arrays.asList("ANA-1", "ANA-2"));
        new BiometricDatabase(arquivo, cifra).salvar(BRUNO, uma("BRUNO-1"));

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(Arrays.asList("ANA-1", "ANA-2"), db.buscar(ANA));
        assertEquals(uma("BRUNO-1"), db.buscar(BRUNO));
        assertEquals(2, db.quantidade());
        assertFalse(db.houveFalhaAoCarregar());
    }

    @Test
    public void emailIgnoraMaiusculasEEspacos() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar("  Ana@Unifor.BR ", uma("ANA"));
        assertEquals(uma("ANA"), db.buscar(ANA));
        assertEquals(db.posicaoDo(ANA), db.salvar("ANA@unifor.br", uma("ANA-NOVA")));
        assertEquals(1, db.quantidade());
    }

    @Test
    public void salvarDeNovoTrocaADigital() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(ANA, Arrays.asList("ANTIGO-1", "ANTIGO-2"));
        db.salvar(ANA, uma("NOVO"));
        assertEquals(uma("NOVO"), new BiometricDatabase(arquivo, cifra).buscar(ANA));
    }

    @Test
    public void remover() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(ANA, uma("TEMPLATE-ANA"));
        assertTrue(db.remover(ANA));
        assertFalse(db.remover(ANA));
        assertNull(new BiometricDatabase(arquivo, cifra).buscar(ANA));
    }

    @Test
    public void arquivoNaoTemOTemplateNemOEmailEmTextoClaro() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(ANA, uma("TEMPLATE-SECRETO"));
        String bruto = new String(Files.readAllBytes(arquivo.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(bruto.contains("TEMPLATE-SECRETO"));
        assertFalse(bruto.contains("unifor"));
    }

    @Test
    public void chaveDiferenteNaoLeEAvisa() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(ANA, uma("TEMPLATE-ANA"));

        KeyGenerator gerador = KeyGenerator.getInstance("AES");
        gerador.init(256);
        SecretKey outra = gerador.generateKey();
        BiometricDatabase db = new BiometricDatabase(arquivo, new CifraAesGcm(outra));

        assertEquals(0, db.quantidade());
        assertTrue(db.houveFalhaAoCarregar());
    }

    @Test
    public void arquivoAlteradoEDetectado() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(ANA, uma("TEMPLATE-ANA"));
        byte[] bytes = Files.readAllBytes(arquivo.toPath());
        bytes[bytes.length - 1] ^= 1;
        Files.write(arquivo.toPath(), bytes);

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertNull(db.buscar(ANA));
        assertTrue(db.houveFalhaAoCarregar());
    }

    @Test
    public void semArquivoComecaVazioSemFalha() {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(0, db.quantidade());
        assertFalse(db.houveFalhaAoCarregar());
    }

    @Test(expected = IllegalArgumentException.class)
    public void recusaAmostraVazia() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(ANA, Arrays.asList("ANA-1", ""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void recusaListaVazia() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(ANA, Collections.emptyList());
    }

    @Test(expected = IllegalArgumentException.class)
    public void recusaEmailVazio() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar("  ", uma("X"));
    }

    /** Digitais com id numérico (versões 1 a 3) não têm e-mail: são descartadas e contadas. */
    @Test
    public void formatoAntigoComIdNumericoEDescartado() throws Exception {
        String antigo = "{\"versao\":3,\"digitais\":["
                + "{\"posicao\":0,\"professorId\":7,\"templates\":[\"VELHO\"]},"
                + "{\"professorId\":9,\"template\":\"MAIS-VELHO\"}]}";
        Files.write(arquivo.toPath(), cifra.cifrar(antigo.getBytes(StandardCharsets.UTF_8)));

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(0, db.quantidade());
        assertEquals(2, db.digitaisDescartadas());
        assertFalse(db.houveFalhaAoCarregar());

        db.salvar(ANA, uma("NOVA"));
        assertEquals("depois de gravar, o arquivo não tem mais as antigas",
                0, new BiometricDatabase(arquivo, cifra).digitaisDescartadas());
    }

    // ------------------------------------------------------------ arrays

    @Test
    public void cadastroAlternaEntreOsArraysParaFicaremIguais() throws Exception {
        BiometricDatabase db = pequeno();
        assertEquals(0, db.salvar(ANA, uma("ANA")));     // array 1, posição 0
        assertEquals(3, db.salvar(BRUNO, uma("BRUNO"))); // array 2, posição 0
        assertEquals(1, db.salvar(CARLA, uma("CARLA"))); // array 1, posição 1
        assertEquals(4, db.salvar(DIEGO, uma("DIEGO")));
        assertArrayEquals(new int[] {2, 2}, db.quantidadePorArray());
        assertEquals(1, db.arrayDa(4));

        // Remover libera a posição; o próximo vai para o array com menos digitais.
        db.remover(CARLA);
        assertEquals(1, db.posicaoLivre());
        assertEquals(1, db.salvar("eva@unifor.br", uma("EVA")));
    }

    @Test
    public void atualizarMantemAMesmaPosicao() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, uma("ANA"));
        db.salvar(BRUNO, uma("BRUNO"));
        assertEquals(3, db.salvar(BRUNO, uma("BRUNO-NOVO")));
        assertEquals(2, db.quantidade());
        assertEquals(3, db.posicaoDo(BRUNO));
    }

    @Test
    public void posicoesSobrevivemAoRecarregar() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, uma("ANA"));
        db.salvar(BRUNO, uma("BRUNO"));
        db.salvar(CARLA, uma("CARLA"));
        db.remover(ANA);

        BiometricDatabase outro = pequeno();
        assertNull(outro.naPosicao(0));
        assertEquals(BRUNO, outro.naPosicao(3).getProfessorEmail());
        assertEquals(CARLA, outro.naPosicao(1).getProfessorEmail());
        assertEquals(0, outro.posicaoLivre());
    }

    @Test
    public void arraysCheiosRecusamNovoProfessor() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra, 2, 1);
        db.salvar(ANA, uma("ANA"));
        db.salvar(BRUNO, uma("BRUNO"));
        assertEquals(-1, db.posicaoLivre());

        // Quem já está no array ainda pode trocar a digital.
        assertEquals(0, db.salvar(ANA, uma("ANA-NOVA")));
        try {
            db.salvar(CARLA, uma("CARLA"));
            fail("Deveria recusar: não há posição livre.");
        } catch (IllegalStateException esperado) {
            assertNull(db.buscar(CARLA));
        }
    }

    @Test
    public void identificarSequencialPercorreTudoEAchaODono() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, Arrays.asList("ANA-1", "ANA-2"));
        db.salvar(BRUNO, Arrays.asList("BRUNO-1", "BRUNO-2"));
        db.salvar(CARLA, Arrays.asList("CARLA-1", "CARLA-2"));

        List<String> comparadas = new ArrayList<>();
        List<String> donos = db.identificar(amostra -> {
            comparadas.add(amostra);
            return amostra.equals("BRUNO-2");
        });

        assertEquals(Collections.singletonList(BRUNO), donos);
        // Passou pelos dois arrays inteiros, e não só até achar o Bruno.
        assertEquals(6, comparadas.size());
    }

    @Test
    public void identificarEmParaleloUsaUmComparadorPorArray() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, uma("ANA"));       // array 1
        db.salvar(BRUNO, uma("BRUNO"));   // array 2
        db.salvar(CARLA, uma("CARLA"));   // array 1
        db.salvar(DIEGO, uma("DIEGO"));   // array 2

        List<String> doPrimeiro = Collections.synchronizedList(new ArrayList<>());
        List<String> doSegundo = Collections.synchronizedList(new ArrayList<>());
        Set<String> threads = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch osDoisRodando = new CountDownLatch(2);
        List<BiometricDatabase.Comparador<InterruptedException>> comparadores = Arrays.asList(
                amostra -> {
                    threads.add(Thread.currentThread().getName());
                    osDoisRodando.countDown();
                    // Só passa se o outro array estiver rodando ao mesmo tempo.
                    if (!osDoisRodando.await(5, TimeUnit.SECONDS)) throw new AssertionError("sequencial");
                    doPrimeiro.add(amostra);
                    return amostra.equals("DIEGO") || amostra.equals("CARLA");
                },
                amostra -> {
                    threads.add(Thread.currentThread().getName());
                    osDoisRodando.countDown();
                    if (!osDoisRodando.await(5, TimeUnit.SECONDS)) throw new AssertionError("sequencial");
                    doSegundo.add(amostra);
                    return amostra.equals("DIEGO") || amostra.equals("CARLA");
                });

        List<String> donos = db.identificar(comparadores);

        assertEquals(Arrays.asList("ANA", "CARLA"), doPrimeiro);
        assertEquals(Arrays.asList("BRUNO", "DIEGO"), doSegundo);
        assertEquals(2, threads.size());
        assertEquals("na ordem das posições", Arrays.asList(CARLA, DIEGO), donos);
    }

    @Test
    public void erroNumaThreadChegaAQuemChamou() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, uma("ANA"));
        db.salvar(BRUNO, uma("BRUNO"));
        List<BiometricDatabase.Comparador<IOException>> comparadores = Arrays.asList(
                amostra -> false,
                amostra -> {
                    throw new IOException("leitor falhou");
                });
        try {
            db.identificar(comparadores);
            fail("Deveria repassar o erro do segundo array.");
        } catch (IOException esperado) {
            assertEquals("leitor falhou", esperado.getMessage());
        }
    }

    @Test
    public void identificarSemAcertoDevolveListaVazia() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(ANA, uma("ANA"));
        assertTrue(db.identificar(amostra -> false).isEmpty());
    }

    @Test
    public void identificarDevolveTodosQueConferem() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, uma("PARECIDA"));
        db.salvar(BRUNO, uma("OUTRA"));
        db.salvar(CARLA, uma("PARECIDA"));
        assertEquals(Arrays.asList(ANA, CARLA), db.identificar(amostra -> amostra.equals("PARECIDA")));
    }

    @Test
    public void amostrasDeExemploParaConferirOParalelo() throws Exception {
        BiometricDatabase db = pequeno();
        db.salvar(ANA, Arrays.asList("A1", "A2"));
        db.salvar(BRUNO, Arrays.asList("B1", "B2"));
        db.salvar(CARLA, Arrays.asList("C1", "C2"));
        assertEquals(2, db.amostrasDeExemplo(2).size());
        assertEquals(Arrays.asList("A1", "A2"), db.amostrasDeExemplo(2).get(0));
    }

    private static List<String> uma(String amostra) {
        return Collections.singletonList(amostra);
    }
}
