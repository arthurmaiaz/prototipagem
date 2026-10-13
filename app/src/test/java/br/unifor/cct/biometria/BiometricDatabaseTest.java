package br.unifor.cct.biometria;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        assertEquals(2, db.todos().size());
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

        assertTrue(db.todos().isEmpty());
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
        assertTrue(db.todos().isEmpty());
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

    private static List<String> uma(String amostra) {
        return Collections.singletonList(amostra);
    }
}
