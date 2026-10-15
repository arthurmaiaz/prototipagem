package br.unifor.cct.biometria;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    @Test
    public void salvaERecarregaDeOutraInstancia() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(1, Arrays.asList("ANA-1", "ANA-2"));
        new BiometricDatabase(arquivo, cifra).salvar(2, uma("BRUNO-1"));

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(Arrays.asList("ANA-1", "ANA-2"), db.buscar(1));
        assertEquals(uma("BRUNO-1"), db.buscar(2));
        assertEquals(2, db.quantidade());
        assertFalse(db.houveFalhaAoCarregar());
    }

    @Test
    public void salvarDeNovoTrocaODigital() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(1, Arrays.asList("ANTIGO-1", "ANTIGO-2"));
        db.salvar(1, uma("NOVO"));
        assertEquals(uma("NOVO"), new BiometricDatabase(arquivo, cifra).buscar(1));
    }

    @Test
    public void remover() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(1, uma("TEMPLATE-ANA"));
        assertTrue(db.remover(1));
        assertFalse(db.remover(1));
        assertNull(new BiometricDatabase(arquivo, cifra).buscar(1));
    }

    @Test
    public void arquivoNaoTemOTemplateEmTextoClaro() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(1, uma("TEMPLATE-SECRETO"));
        String bruto = new String(Files.readAllBytes(arquivo.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(bruto.contains("TEMPLATE-SECRETO"));
        assertFalse(bruto.contains("professorId"));
    }

    @Test
    public void chaveDiferenteNaoLeEAvisa() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(1, uma("TEMPLATE-ANA"));

        KeyGenerator gerador = KeyGenerator.getInstance("AES");
        gerador.init(256);
        SecretKey outra = gerador.generateKey();
        BiometricDatabase db = new BiometricDatabase(arquivo, new CifraAesGcm(outra));

        assertEquals(0, db.quantidade());
        assertTrue(db.houveFalhaAoCarregar());
    }

    @Test
    public void arquivoAlteradoEDetectado() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(1, uma("TEMPLATE-ANA"));
        byte[] bytes = Files.readAllBytes(arquivo.toPath());
        bytes[bytes.length - 1] ^= 1;
        Files.write(arquivo.toPath(), bytes);

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertNull(db.buscar(1));
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
        new BiometricDatabase(arquivo, cifra).salvar(1, Arrays.asList("ANA-1", ""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void recusaListaVazia() throws Exception {
        new BiometricDatabase(arquivo, cifra).salvar(1, Collections.emptyList());
    }

    /** Arquivos gravados antes da versão 2 tinham um "template" só por professor. */
    @Test
    public void leFormatoAntigoComUmTemplate() throws Exception {
        String antigo = "{\"versao\":1,\"digitais\":[{\"professorId\":7,\"template\":\"VELHO\"}]}";
        Files.write(arquivo.toPath(), cifra.cifrar(antigo.getBytes(StandardCharsets.UTF_8)));

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(uma("VELHO"), db.buscar(7));
        assertFalse(db.houveFalhaAoCarregar());
    }

    // ------------------------------------------------- array de posições

    @Test
    public void cadastroOcupaAPrimeiraPosicaoLivre() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(0, db.posicaoLivre());
        assertEquals(0, db.salvar(10, uma("ANA")));
        assertEquals(1, db.salvar(20, uma("BRUNO")));
        assertEquals(2, db.salvar(30, uma("CARLA")));

        // Remover o do meio libera a posição 1, que é a próxima a ser usada.
        db.remover(20);
        assertEquals(1, db.posicaoLivre());
        assertEquals(1, db.salvar(40, uma("DIEGO")));
        assertEquals(3, db.posicaoLivre());
    }

    @Test
    public void atualizarMantemAMesmaPosicao() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(10, uma("ANA"));
        db.salvar(20, uma("BRUNO"));
        assertEquals(1, db.salvar(20, uma("BRUNO-NOVO")));
        assertEquals(2, db.quantidade());
        assertEquals(1, db.posicaoDo(20));
    }

    @Test
    public void posicoesSobrevivemAoRecarregar() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(10, uma("ANA"));
        db.salvar(20, uma("BRUNO"));
        db.salvar(30, uma("CARLA"));
        db.remover(10);

        BiometricDatabase outro = new BiometricDatabase(arquivo, cifra);
        assertNull(outro.naPosicao(0));
        assertEquals(20, outro.naPosicao(1).getProfessorId());
        assertEquals(30, outro.naPosicao(2).getProfessorId());
        assertEquals(0, outro.posicaoLivre());
    }

    @Test
    public void arrayCheioRecusaNovoProfessor() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra, 2);
        db.salvar(10, uma("ANA"));
        db.salvar(20, uma("BRUNO"));
        assertEquals(-1, db.posicaoLivre());

        // Quem já está no array ainda pode trocar a digital.
        assertEquals(0, db.salvar(10, uma("ANA-NOVA")));
        try {
            db.salvar(30, uma("CARLA"));
            fail("Deveria recusar: não há posição livre.");
        } catch (IllegalStateException esperado) {
            assertNull(db.buscar(30));
        }
    }

    @Test
    public void identificarPercorreOArrayEAchaODono() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(10, Arrays.asList("ANA-1", "ANA-2"));
        db.salvar(20, Arrays.asList("BRUNO-1", "BRUNO-2"));
        db.salvar(30, Arrays.asList("CARLA-1", "CARLA-2"));

        List<String> comparadas = new ArrayList<>();
        List<Integer> donos = db.identificar(amostra -> {
            comparadas.add(amostra);
            return amostra.equals("BRUNO-2");
        });

        assertEquals(Collections.singletonList(20), donos);
        // Passou por todas as posições, e não só até achar o Bruno.
        assertEquals(Arrays.asList("ANA-1", "ANA-2", "BRUNO-1", "BRUNO-2", "CARLA-1", "CARLA-2"),
                comparadas);
    }

    @Test
    public void identificarSemAcertoDevolveListaVazia() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(10, uma("ANA"));
        assertTrue(db.identificar(amostra -> false).isEmpty());
    }

    @Test
    public void identificarDevolveTodosQueConferem() throws Exception {
        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        db.salvar(10, uma("PARECIDA"));
        db.salvar(20, uma("OUTRA"));
        db.salvar(30, uma("PARECIDA"));
        assertEquals(Arrays.asList(10, 30), db.identificar(amostra -> amostra.equals("PARECIDA")));
    }

    @Test
    public void formatoV2SemPosicaoVaiParaAsPrimeirasLivres() throws Exception {
        String v2 = "{\"versao\":2,\"digitais\":["
                + "{\"professorId\":7,\"templates\":[\"A\"]},"
                + "{\"professorId\":9,\"templates\":[\"B\"]}]}";
        Files.write(arquivo.toPath(), cifra.cifrar(v2.getBytes(StandardCharsets.UTF_8)));

        BiometricDatabase db = new BiometricDatabase(arquivo, cifra);
        assertEquals(0, db.posicaoDo(7));
        assertEquals(1, db.posicaoDo(9));
        assertEquals(2, db.posicaoLivre());
    }

    private static List<String> uma(String amostra) {
        return Collections.singletonList(amostra);
    }
}
