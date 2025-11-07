package com.In.Filmes.service;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ServicoPmmlClusters {

    public static class Cluster {
        public String id;           // id usado para comparação (preferencialmente número se houver)
        public String originalId;   // id original lido do PMML (name ou id)
        public double[] centroide;
    }

    private List<String> nomesCampos;
    private List<Cluster> clusters;

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    public void carregarPmml(String caminhoPmml) throws Exception {
        File f = new File(caminhoPmml);
        if (!f.exists()) throw new IllegalArgumentException("PMML não encontrado: " + caminhoPmml);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        try (Reader r = new InputStreamReader(new FileInputStream(f), "UTF-8")) {
            Document doc = db.parse(new InputSource(r));
            Element root = doc.getDocumentElement();

            // MiningSchema -> nomes dos campos (ordem)
            NodeList mining = root.getElementsByTagNameNS("*", "MiningSchema");
            nomesCampos = new ArrayList<>();
            if (mining.getLength() > 0) {
                NodeList fields = ((Element) mining.item(0)).getElementsByTagNameNS("*", "MiningField");
                for (int i = 0; i < fields.getLength(); i++) {
                    Element mf = (Element) fields.item(i);
                    String name = mf.getAttribute("name");
                    if (name != null && !name.isBlank()) nomesCampos.add(name);
                }
            }

            // Clusters
            NodeList clusterNodes = root.getElementsByTagNameNS("*", "Cluster");
            clusters = new ArrayList<>();
            for (int i = 0; i < clusterNodes.getLength(); i++) {
                Element c = (Element) clusterNodes.item(i);
                Cluster cl = new Cluster();

                // tenta id primeiro, se não existir tenta name
                String idAttr = c.getAttribute("id");
                String nameAttr = c.getAttribute("name");
                String orig = (idAttr != null && !idAttr.isBlank()) ? idAttr : (nameAttr != null ? nameAttr : "");
                cl.originalId = orig == null ? "" : orig;

                // extrai dígitos do nome (ex: cluster_0 -> 0) para facilitar matching contra coluna 'cluster' do Excel
                String chosen = cl.originalId;
                Matcher m = DIGITS.matcher(cl.originalId == null ? "" : cl.originalId);
                if (m.find()) {
                    chosen = m.group(1); // pega os dígitos
                }
                cl.id = chosen == null ? "" : chosen;

                // ler array (atributo array ou elemento Array)
                String arrayAttr = c.getAttribute("array");
                if (arrayAttr != null && !arrayAttr.isBlank()) {
                    cl.centroide = parseArray(arrayAttr);
                } else {
                    NodeList arrays = c.getElementsByTagNameNS("*", "Array");
                    if (arrays.getLength() > 0) {
                        String text = arrays.item(0).getTextContent();
                        cl.centroide = parseArray(text);
                    } else {
                        cl.centroide = new double[0];
                    }
                }
                clusters.add(cl);
            }
        }
    }

    private double[] parseArray(String texto) {
        if (texto == null) return new double[0];
        String[] partes = texto.trim().split("[ ,\\t\\n\\r]+");
        double[] a = new double[partes.length];
        for (int i = 0; i < partes.length; i++) {
            try {
                a[i] = Double.parseDouble(partes[i]);
            } catch (Exception e) {
                a[i] = Double.NaN;
            }
        }
        return a;
    }

    /**
     * Retorna id do cluster previsto (preferencialmente valor numérico extraído, ex: "0", "1", ...).
     * Se não houver match de tamanho do vetor de centróide, pula o cluster.
     */
    public String preverCluster(double[] vetorEntrada) {
        if (clusters == null || clusters.isEmpty()) return null;
        double melhor = Double.POSITIVE_INFINITY;
        String idMelhor = null;
        for (Cluster c : clusters) {
            if (c.centroide == null || c.centroide.length != vetorEntrada.length) continue;
            double soma = 0.0;
            for (int i = 0; i < vetorEntrada.length; i++) {
                double d = vetorEntrada[i] - c.centroide[i];
                soma += d * d;
            }
            double dist = Math.sqrt(soma);
            if (dist < melhor) { melhor = dist; idMelhor = c.id; }
        }
        return idMelhor;
    }

    public List<String> getNomesCampos() { return nomesCampos; }

    // Opcional: fornecer clusters originais (debug)
    public List<Cluster> getClusters() { return clusters; }
}
