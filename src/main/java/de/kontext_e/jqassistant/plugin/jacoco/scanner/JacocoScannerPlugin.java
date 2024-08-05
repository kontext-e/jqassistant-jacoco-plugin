package de.kontext_e.jqassistant.plugin.jacoco.scanner;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.ScannerPlugin;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.plugin.common.api.model.FileDescriptor;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.common.api.scanner.filesystem.FileResource;
import com.buschmais.jqassistant.plugin.java.api.scanner.SignatureHelper;
import de.kontext_e.jqassistant.plugin.jacoco.jaxb.*;
import de.kontext_e.jqassistant.plugin.jacoco.store.descriptor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static java.lang.String.format;

/**
 * @author jn4, Kontext E GmbH, 11.02.14
 */
@ScannerPlugin.Requires(FileDescriptor.class)
public class JacocoScannerPlugin extends AbstractScannerPlugin<FileResource,JacocoReportDescriptor> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JacocoScannerPlugin.class);
    public static final String JQASSISTANT_PLUGIN_JACOCO_FILENAME = "jqassistant.plugin.jacoco.filename";
    public static final String JQASSISTANT_PLUGIN_JACOCO_DIRNAME = "jqassistant.plugin.jacoco.dirname";
    private final JAXBContext jaxbContext;
    private String jacocoDirName = "jacoco";
    private String jacocoFileName = "jacocoTestReport.xml";

    public JacocoScannerPlugin() {
        try {
            jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot create JAXB context.", e);
        }
    }

    @Override
    protected void configure() {
        super.configure();

        if(getProperties().containsKey(JQASSISTANT_PLUGIN_JACOCO_DIRNAME)) {
            jacocoDirName = (String) getProperties().get(JQASSISTANT_PLUGIN_JACOCO_DIRNAME);
        }
        if(getProperties().containsKey(JQASSISTANT_PLUGIN_JACOCO_FILENAME)) {
            jacocoFileName = (String) getProperties().get(JQASSISTANT_PLUGIN_JACOCO_FILENAME);
        }
        LOGGER.info(format("Jacoco plugin looks for files named %s and files in directory %s", jacocoFileName, jacocoDirName));
    }

    @Override
    public boolean accepts(final FileResource item, String path, Scope scope) {
        try {
            boolean accepted = acceptsPath(path);
            if(accepted) {
                LOGGER.info("Jacoco plugin accepted "+path);
            }
            return accepted;
        } catch (NullPointerException e) {
            // could do a lengthy null check at beginning or do it the short dirty way
            return false;
        } catch (Exception e) {
            LOGGER.error("Error while checking path: "+e, e);
            return false;
        }
    }

    boolean acceptsPath(String path) {
        return path.endsWith(jacocoFileName) || parentDirectoryHasAcceptableName(path);
    }

    private boolean parentDirectoryHasAcceptableName(String path) {
        if(!path.endsWith(".xml")) {
            return false;
        }

        final String[] parts = path.split("/");
        if(parts == null || parts.length < 2) {
            return false;
        }

        if(jacocoDirName == null) {
            return false;
        }

        String parentName = parts[parts.length - 2];

        return jacocoDirName.equalsIgnoreCase(parentName);
    }

    @Override
    public JacocoReportDescriptor scan(final FileResource file, String path, Scope scope, Scanner scanner) throws IOException {
        LOGGER.debug("Jacoco plugin scans "+path);
		FileDescriptor fileDescriptor = scanner.getContext().getCurrentDescriptor();
		final JacocoReportDescriptor jacocoReportDescriptor = scanner.getContext().getStore().addDescriptorType(fileDescriptor, JacocoReportDescriptor.class);
        final ReportType reportType = unmarshalJacocoXml(file.createStream());
        readPackages(scanner.getContext().getStore(), reportType, jacocoReportDescriptor);
        return jacocoReportDescriptor;
    }

    private void readPackages(final Store store, final ReportType reportType, final JacocoReportDescriptor jacocoReportDescriptor) {
        for (PackageType packageType : reportType.getPackage()) {
            final JacocoPackageDescriptor jacocoPackageDescriptor = store.create(JacocoPackageDescriptor.class);
            jacocoPackageDescriptor.setName(packageType.getName());
            readClasses(store, packageType, jacocoPackageDescriptor);
            jacocoReportDescriptor.getJacocoPackages().add(jacocoPackageDescriptor);
        }
    }

    private void readClasses(final Store store, final PackageType packageType, final JacocoPackageDescriptor jacocoPackageDescriptor) {
        for (ClassType classType : packageType.getClazz()) {
            final JacocoClassDescriptor jacocoClassDescriptor = store.create(JacocoClassDescriptor.class);
            jacocoClassDescriptor.setName(classType.getName());
            jacocoClassDescriptor.setFullQualifiedName(jacocoClassDescriptor.getName().replaceAll("/", "."));
            readMethods(store, classType, jacocoClassDescriptor);
            jacocoPackageDescriptor.getJacocoClasses().add(jacocoClassDescriptor);
        }
    }

    private void readMethods(final Store store, final ClassType classType, final JacocoClassDescriptor jacocoClassDescriptor) {
        for (MethodType methodType : classType.getMethod()) {
            final JacocoMethodDescriptor jacocoMethodDescriptor = store.create(JacocoMethodDescriptor.class);
            jacocoMethodDescriptor.setName(methodType.getName());
			jacocoMethodDescriptor.setSignature(SignatureHelper.getMethodSignature(methodType.getName(), methodType.getDesc()));
            jacocoMethodDescriptor.setLine(methodType.getLine());
            jacocoClassDescriptor.getJacocoMethods().add(jacocoMethodDescriptor);
            readCounters(store, methodType, jacocoMethodDescriptor);
        }

    }

    private void readCounters(final Store store, final MethodType methodType, final JacocoMethodDescriptor jacocoMethodDescriptor) {
        for (CounterType counterType : methodType.getCounter()) {
            final JacocoCounterDescriptor jacocoCounterDescriptor = store.create(JacocoCounterDescriptor.class);
            jacocoCounterDescriptor.setType(counterType.getType());
            jacocoCounterDescriptor.setMissed(Long.valueOf(counterType.getMissed()));
            jacocoCounterDescriptor.setCovered(Long.valueOf(counterType.getCovered()));
            jacocoMethodDescriptor.getJacocoCounters().add(jacocoCounterDescriptor);
        }
    }

	protected ReportType unmarshalJacocoXml(final InputStream streamSource) throws IOException {
        ReportType reportType;
        try {
            // use own SAXSource to prevent reading of jacoco's report.dtd
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            parserFactory.setValidating(false);
            parserFactory.setFeature("http://xml.org/sax/features/validation", false);
            parserFactory.setFeature("http://apache.org/xml/features/validation/schema", false);
            parserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            parserFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XMLReader xmlReader = parserFactory.newSAXParser().getXMLReader();
            InputSource inputSource = new InputSource(new InputStreamReader(streamSource));
            SAXSource saxSource = new SAXSource(xmlReader, inputSource);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            reportType = unmarshaller.unmarshal(saxSource, ReportType.class).getValue();
        } catch (JAXBException |SAXException |ParserConfigurationException e ) {
            throw new IOException("Cannot read model descriptor.", e);
        }
        return reportType;
    }
}
