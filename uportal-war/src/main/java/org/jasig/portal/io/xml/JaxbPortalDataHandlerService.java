/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.portal.io.xml;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.DirectoryScanner;
import org.jasig.portal.utils.AntPatternFileFilter;
import org.jasig.portal.utils.ConcurrentDirectoryScanner;
import org.jasig.portal.xml.StaxUtils;
import org.jasig.portal.xml.XmlUtilities;
import org.jasig.portal.xml.stream.BufferedXMLEventReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.oxm.Marshaller;
import org.springframework.oxm.Unmarshaller;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;

/**
 * Pulls together {@link IPortalDataType}, {@link IDataUpgrader}, and {@link IDataImporter}
 * implementations to handle data upgrade, import, export and removal operations.
 * 
 * TODO better error handling, try to figure out what went wrong and provide a solution in the exception message
 * 
 * @author Eric Dalquist
 * @version $Revision$
 */
@Service("portalDataHandlerService")
public class JaxbPortalDataHandlerService implements IPortalDataHandlerService, ResourceLoaderAware {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    
    // Order in which data must be imported
    private List<PortalDataKey> dataKeyImportOrder = Collections.emptyList();
    // Map to lookup the associated IPortalDataType for each known PortalDataKey
    private Map<PortalDataKey, IPortalDataType> dataKeyTypes = Collections.emptyMap();
    
    // Ant path matcher patterns that a file must match when scanning directories (unless a pattern is explicitly specified)
    private Set<String> dataFileIncludes = Collections.emptySet();
    private Set<String> dataFileExcludes = ImmutableSet.copyOf(DirectoryScanner.getDefaultExcludes());
    
    // Data upgraders mapped by PortalDataKey
    private Map<PortalDataKey, IDataUpgrader> portalDataUpgraders = Collections.emptyMap();
    // Data importers mapped by PortalDataKey
    private Map<PortalDataKey, IDataImporter<Object>> portalDataImporters = Collections.emptyMap();

    // All portal data types available for export
    private Set<IPortalDataType> exportPortalDataTypes = Collections.emptySet();
    // Data exporters mapped by IPortalDateType#getTypeId()
    private Map<String, IDataExporter<Object>> portalDataExporters = Collections.emptyMap();
    
    // All portal data types available for delete
    private Set<IPortalDataType> deletePortalDataTypes = Collections.emptySet();
    // Data deleters mapped by IPortalDateType#getTypeId()
    private Map<String, IDataDeleter<Object>> portalDataDeleters = Collections.emptyMap();

    
    private org.jasig.portal.utils.DirectoryScanner directoryScanner;
    private ExecutorService importExportThreadPool;
    private XmlUtilities xmlUtilities;
    private ResourceLoader resourceLoader;
    
    private long maxWait = -1;
    private TimeUnit maxWaitTimeUnit = TimeUnit.MILLISECONDS;

    @Autowired
    public void setXmlUtilities(XmlUtilities xmlUtilities) {
        this.xmlUtilities = xmlUtilities;
    }
    
    @Autowired
    public void setImportExportThreadPool(@Qualifier("importExportThreadPool") ExecutorService importExportThreadPool) {
        this.importExportThreadPool = importExportThreadPool;
        this.directoryScanner = new ConcurrentDirectoryScanner(this.importExportThreadPool);
    }

    /**
     * Maximum time to wait for an import, export, or delete to execute. 
     */
    public void setMaxWait(long maxWait) {
        this.maxWait = maxWait;
    }

    /**
     * {@link TimeUnit} for {@link #setMaxWait(long)} value.
     */
    public void setMaxWaitTimeUnit(TimeUnit maxWaitTimeUnit) {
        this.maxWaitTimeUnit = maxWaitTimeUnit;
    }
    
    /**
     * Order in which data types should be imported.
     */
    @javax.annotation.Resource(name="dataTypeImportOrder")
    public void setDataTypeImportOrder(List<IPortalDataType> dataTypeImportOrder) {
        final ArrayList<PortalDataKey> dataKeyImportOrder = new ArrayList<PortalDataKey>(dataTypeImportOrder.size() * 2);
        final Map<PortalDataKey, IPortalDataType> dataKeyTypes = new LinkedHashMap<PortalDataKey, IPortalDataType>(dataTypeImportOrder.size() * 2);
        
        for (final IPortalDataType portalDataType : dataTypeImportOrder) {
            final List<PortalDataKey> supportedDataKeys = portalDataType.getDataKeyImportOrder();
            for (final PortalDataKey portalDataKey : supportedDataKeys) {
                dataKeyImportOrder.add(portalDataKey);
                dataKeyTypes.put(portalDataKey, portalDataType);
            }
        }
        
        dataKeyImportOrder.trimToSize();
        this.dataKeyImportOrder = Collections.unmodifiableList(dataKeyImportOrder);
        this.dataKeyTypes = Collections.unmodifiableMap(dataKeyTypes);
    }
    
    /**
     * Ant path matching patterns that files must match to be included
     */
    @javax.annotation.Resource(name="dataFileIncludes")
    public void setDataFileIncludes(Set<String> dataFileIncludes) {
        this.dataFileIncludes = dataFileIncludes;
    }
    
    /**
     * Ant path matching patterns that exclude matched files. Defaults to {@link DirectoryScanner#addDefaultExcludes()}
     */
    public void setDataFileExcludes(Set<String> dataFileExcludes) {
        this.dataFileExcludes = dataFileExcludes;
    }

    /**
     * {@link IDataImporter} implementations to delegate import operations to. 
     */
    @SuppressWarnings("unchecked")
    @Autowired(required=false)
    public void setDataImporters(Collection<IDataImporter<? extends Object>> dataImporters) {
        final Map<PortalDataKey, IDataImporter<Object>> dataImportersMap = new LinkedHashMap<PortalDataKey, IDataImporter<Object>>();
        
        for (final IDataImporter<?> dataImporter : dataImporters) {
            final Set<PortalDataKey> importDataKeys = dataImporter.getImportDataKeys();
            
            for (final PortalDataKey importDataKey : importDataKeys) {
                this.logger.debug("Registering IDataImporter for '{}' - {}", new Object[] {importDataKey, dataImporter});
                final IDataImporter<Object> existing = dataImportersMap.put(importDataKey, (IDataImporter<Object>)dataImporter);
                if (existing != null) {
                    this.logger.warn("Duplicate IDataImporter PortalDataKey for {} Replacing {} with {}", 
                            new Object[] {importDataKey, existing, dataImporter});
                }
            }
        }
        
        this.portalDataImporters = Collections.unmodifiableMap(dataImportersMap);
    }

    /**
     * {@link IDataExporter} implementations to delegate export operations to. 
     */
    @SuppressWarnings("unchecked")
    @Autowired(required=false)
    public void setDataExporters(Collection<IDataExporter<? extends Object>> dataExporters) {
        final Map<String, IDataExporter<Object>> dataExportersMap = new LinkedHashMap<String, IDataExporter<Object>>();
        
        final Set<IPortalDataType> portalDataTypes = new LinkedHashSet<IPortalDataType>();
        
        for (final IDataExporter<?> dataImporter : dataExporters) {
            final IPortalDataType portalDataType = dataImporter.getPortalDataType();
            final String typeId = portalDataType.getTypeId();
            
            this.logger.debug("Registering IDataExporter for '{}' - {}", new Object[] {typeId, dataImporter});
            final IDataExporter<Object> existing = dataExportersMap.put(typeId, (IDataExporter<Object>)dataImporter);
            if (existing != null) {
                this.logger.warn("Duplicate IDataExporter typeId for {} Replacing {} with {}", 
                        new Object[] {typeId, existing, dataImporter});
            }
            
            portalDataTypes.add(portalDataType);
        }
        
        this.portalDataExporters = Collections.unmodifiableMap(dataExportersMap);
        this.exportPortalDataTypes = Collections.unmodifiableSet(portalDataTypes);
    }

    /**
     * {@link IDataDeleter} implementations to delegate delete operations to. 
     */
    @SuppressWarnings("unchecked")
    @Autowired(required=false)
    public void setDataDeleters(Collection<IDataDeleter<? extends Object>> dataDeleters) {
        final Map<String, IDataDeleter<Object>> dataDeletersMap = new LinkedHashMap<String, IDataDeleter<Object>>();
        
        final Set<IPortalDataType> portalDataTypes = new LinkedHashSet<IPortalDataType>();
        
        for (final IDataDeleter<?> dataImporter : dataDeleters) {
            final IPortalDataType portalDataType = dataImporter.getPortalDataType();
            final String typeId = portalDataType.getTypeId();
            
            this.logger.debug("Registering IDataDeleter for '{}' - {}", new Object[] {typeId, dataImporter});
            final IDataDeleter<Object> existing = dataDeletersMap.put(typeId, (IDataDeleter<Object>)dataImporter);
            if (existing != null) {
                this.logger.warn("Duplicate IDataDeleter typeId for {} Replacing {} with {}", 
                        new Object[] {typeId, existing, dataImporter});
            }
            
            portalDataTypes.add(portalDataType);
        }
        
        this.portalDataDeleters = Collections.unmodifiableMap(dataDeletersMap);
        this.deletePortalDataTypes = Collections.unmodifiableSet(portalDataTypes);
    }
    
    /**
     * {@link IDataUpgrader} implementations to delegate upgrade operations to. 
     */
    @Autowired(required=false)
    public void setDataUpgraders(Collection<IDataUpgrader> dataUpgraders) {
        final Map<PortalDataKey, IDataUpgrader> dataUpgraderMap = new LinkedHashMap<PortalDataKey, IDataUpgrader>();
        
        for (final IDataUpgrader dataUpgrader : dataUpgraders) {
            final Set<PortalDataKey> upgradeDataKeys = dataUpgrader.getSourceDataTypes();
            for (final PortalDataKey upgradeDataKey : upgradeDataKeys) {
                this.logger.debug("Registering IDataUpgrader for '{}' - {}", upgradeDataKey, dataUpgrader);
                final IDataUpgrader existing = dataUpgraderMap.put(upgradeDataKey, dataUpgrader);
                if (existing != null) {
                    this.logger.warn("Duplicate IDataUpgrader PortalDataKey for {} Replacing {} with {}", 
                            new Object[] {upgradeDataKey, existing, dataUpgrader});
                }
            }
        }
        
        this.portalDataUpgraders = Collections.unmodifiableMap(dataUpgraderMap);
    }
    
    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
    
    
    @Override
    public void importData(File directory, String pattern, final BatchImportOptions options) {
        if (!directory.exists()) {
            throw new IllegalArgumentException("The specified directory '" + directory + "' does not exist");
        }
        
        //Create the file filter to use when searching for files to import
        final FileFilter fileFilter;
        if (pattern != null) {
            fileFilter = new AntPatternFileFilter(true, false, pattern, this.dataFileExcludes);
        }
        else {
            fileFilter = new AntPatternFileFilter(true, false, this.dataFileIncludes, this.dataFileExcludes);
        }
        
        //Map of files to import, grouped by type
        final ConcurrentMap<PortalDataKey, Queue<Resource>> dataToImport = new ConcurrentHashMap<PortalDataKey, Queue<Resource>>();
        
        //Scan the specified directory for files to import
        logger.info("Scanning for files to Import from: {}", directory);
        this.directoryScanner.scanDirectoryNoResults(directory, fileFilter, 
                new PortalDataKeyFileProcessor(this.dataKeyTypes, dataToImport, options));
        
        final boolean failOnError = options != null ? options.isFailOnError() : true;
        
        //Import the data files
        for (final PortalDataKey portalDataKey : this.dataKeyImportOrder) {
            final Queue<Resource> files = dataToImport.remove(portalDataKey);
            if (files == null) {
                continue;
            }

            final Queue<ImportFuture<?>> importFutures = new LinkedList<ImportFuture<?>>();
            
            final int fileCount = files.size();
            logger.info("Importing {} files of type {}", fileCount, portalDataKey);
            
            
            for (final Resource file : files) {
                //Check for completed futures on every iteration, needed to fail as fast as possible on an import exception
                waitForFutures(importFutures, failOnError, false);
                
                //Submit the import task
                final Future<?> importFuture = this.importExportThreadPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        importData(file, portalDataKey);
                    }
                });
                
                //Add the future for tracking
                importFutures.offer(new ImportFuture(importFuture, file));
            }
            
            //Wait for all of the imports on of this type to complete
            waitForFutures(importFutures, failOnError, true);
        }
        
        if (!dataToImport.isEmpty()) {
            throw new IllegalStateException("The following PortalDataKeys are not listed in the dataTypeImportOrder List: " + dataToImport.keySet());
        }
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.io.xml.IDataImportExportService#importData(java.lang.String)
     */
    @Override
    public void importData(String resourceLocation) {
        final Resource resource = this.resourceLoader.getResource(resourceLocation);
        this.importData(resource);
    }

    @Override
    public void importData(final Resource resource) {
        this.importData(resource, null);
    }
    
    /**
     * @param portalDataKey Optional PortalDataKey to use, useful for batch imports where post-processing of keys has already take place
     */
    protected final void importData(final Resource resource, final PortalDataKey portalDataKey) {
        final InputStream resourceStream;
        try {
            resourceStream = resource.getInputStream();
        }
        catch (IOException e) {
            throw new RuntimeException("Could not load InputStream for resource: " + resource, e);
        }
        
        try {
            String resourceUri;
            try {
                resourceUri = resource.getURI().toString();
            }
            catch (FileNotFoundException e) {
                resourceUri = resource.getDescription();
            }
            catch (IOException e) {
                throw new RuntimeException("Could not create URI for resource: " + resource, e);
            }
            
            this.importData(resource, new StreamSource(resourceStream, resourceUri), portalDataKey);
        }
        finally {
            IOUtils.closeQuietly(resourceStream);
        }
    }

    /* (non-Javadoc)
     * @see org.jasig.portal.io.xml.IEntityImportService#importEntity(javax.xml.transform.Source)
     */
    protected final void importData(final Resource resource, final Source source, PortalDataKey portalDataKey) {
        //Get a StAX reader for the source to determine info about the data to import
        final BufferedXMLEventReader bufferedXmlEventReader = createSourceXmlEventReader(source);
        
        //If no PortalDataKey was passed build it from the source
        if (portalDataKey == null) {
            final StartElement rootElement = StaxUtils.getRootElement(bufferedXmlEventReader);
            portalDataKey = new PortalDataKey(rootElement);
            bufferedXmlEventReader.reset();
        }
        
        //Post Process the PortalDataKey to see if more complex import operations are needed
        final IPortalDataType portalDataType = this.dataKeyTypes.get(portalDataKey);
        if (portalDataType == null) {
            throw new RuntimeException("No IPortalDataType configured for " + portalDataKey + ", the resource will be ignored: " + resource);
        }
        final Set<PortalDataKey> postProcessedPortalDataKeys = portalDataType.postProcessPortalDataKey(resource, portalDataKey, bufferedXmlEventReader);
        bufferedXmlEventReader.reset();
        
        //If only a single result from post processing import
        if (postProcessedPortalDataKeys.size() == 1) {
            this.importOrUpgradeData(resource, DataAccessUtils.singleResult(postProcessedPortalDataKeys), bufferedXmlEventReader);
        }
        //If multiple results from post processing ordering is needed
        else {
            //Iterate over the data key order list to run the imports in the correct order
            for (final PortalDataKey orderedPortalDataKey : this.dataKeyImportOrder) {
                if (postProcessedPortalDataKeys.contains(orderedPortalDataKey)) {
                    //Reset the to start of the XML document for each import/upgrade call
                    bufferedXmlEventReader.reset();
                    this.importOrUpgradeData(resource, orderedPortalDataKey, bufferedXmlEventReader);
                }
            }
        }
    }
    
    /**
     * Run the import/update process on the data
     */
    protected final void importOrUpgradeData(Resource resource, PortalDataKey portalDataKey, XMLEventReader xmlEventReader) {
        //See if there is a registered importer for the data, if so import
        final IDataImporter<Object> dataImporterExporter = this.portalDataImporters.get(portalDataKey);
        if (dataImporterExporter != null) {
            this.logger.debug("Importing: {}", resource);
            final Object data = unmarshallData(xmlEventReader, dataImporterExporter);
            dataImporterExporter.importData(data);
            this.logger.info("Imported : {}", resource);
            return;
        }
        
        //No importer, see if there is an upgrader, if so upgrade
        final IDataUpgrader dataUpgrader = this.portalDataUpgraders.get(portalDataKey);
        if (dataUpgrader != null) {
            this.logger.debug("Upgrading: {}", resource);
            
            final StAXSource staxSource;
            try {
                staxSource = new StAXSource(xmlEventReader);
            }
            catch (XMLStreamException e) {
                throw new RuntimeException("Failed to create StAXSource from original XML reader", e);
            }
            
            final DOMResult result = new DOMResult();
            final boolean doImport = dataUpgrader.upgradeData(staxSource, result);
            if (doImport) {
                this.logger.info("Upgraded: {}", resource);
                //If the upgrader didn't handle the import as well wrap the result DOM in a new Source and start the import process over again
                final org.w3c.dom.Node node = result.getNode();
                final DOMSource upgradedSource = new DOMSource(node);
                this.importData(resource, upgradedSource, null);
            }
            else {
                this.logger.info("Upgraded and Imported: {}", resource);
            }
            return;
        }
        
        //No importer or upgrader found, fail
        throw new IllegalArgumentException("Provided data " + portalDataKey + " has no registered importer or upgrader support: " + resource);
    }

    protected Object unmarshallData(final XMLEventReader bufferedXmlEventReader, final IDataImporter<Object> dataImporterExporter) {
        final Unmarshaller unmarshaller = dataImporterExporter.getUnmarshaller();
        
        try {
            final StAXSource source = new StAXSource(bufferedXmlEventReader);
            return unmarshaller.unmarshal(source);
        }
        catch (XmlMappingException e) {
            throw new RuntimeException("Failed to map provided XML to portal data", e);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read the provided XML data", e);
        }
        catch (XMLStreamException e) {
            throw new RuntimeException("Failed to create StAX Source to read XML data", e);
        }
    }

    protected BufferedXMLEventReader createSourceXmlEventReader(final Source source) {
        final XMLInputFactory xmlInputFactory = this.xmlUtilities.getXmlInputFactory();
        final XMLEventReader xmlEventReader;
        try {
            xmlEventReader = xmlInputFactory.createXMLEventReader(source);
        }
        catch (XMLStreamException e) {
            throw new RuntimeException("Failed to create XML Event Reader for data Source", e);
        }
        return new BufferedXMLEventReader(xmlEventReader, -1);
    }

    @Override
    public Iterable<IPortalDataType> getPortalDataTypes() {
        return this.exportPortalDataTypes;
    }

    @Override
    public Iterable<? extends IPortalData> getPortalData(String typeId) {
        final IDataExporter<Object> dataImporterExporter = getPortalDataExporter(typeId);
        return dataImporterExporter.getPortalData();
    }

    @Override
    public String exportData(String typeId, String dataId, Result result) {
        final IDataExporter<Object> portalDataExporter = this.getPortalDataExporter(typeId);
        final Object data = portalDataExporter.exportData(dataId);
        if (data == null) {
            return null;
        }
        
        final Marshaller marshaller = portalDataExporter.getMarshaller();
        try {
            marshaller.marshal(data, result);
            return portalDataExporter.getFileName(data);
        }
        catch (XmlMappingException e) {
            throw new RuntimeException("Failed to map provided portal data to XML", e);
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to write the provided XML data", e);
        }
    }
    
    @Override
    public boolean exportData(String typeId, String dataId, File directory) {
        directory.mkdirs();
        
        final File exportTempFile;
        try {
            exportTempFile = File.createTempFile(StringUtils.rightPad(dataId, 2, '-') + "-",  "." + typeId, directory);
        }
        catch (IOException e) {
            throw new RuntimeException("Could not create temp file to export " + typeId + " " + dataId, e);
        }
        
        try {
            final String fileName = this.exportData(typeId, dataId, new StreamResult(exportTempFile));
            if (fileName != null) {
                final File destFile = new File(directory, fileName + "." + typeId + ".xml");
                if (destFile.exists()) {
                    logger.warn("Exporting " + typeId + " " + dataId + " but destination file already exists, it will be overwritten: " + destFile);
                    destFile.delete();
                }
                FileUtils.moveFile(exportTempFile, destFile);
                logger.info("Exported: {}", destFile);
                
                return true;
            }
            
            return false;
        }
        catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException)e;
            }
            
            throw new RuntimeException("Failed to export " + typeId + " " + dataId, e);
        }
        finally {
            FileUtils.deleteQuietly(exportTempFile);
        }
    }

    @Override
    public void exportAllDataOfType(Set<String> typeIds, File directory) {
        
        final Queue<ExportFuture<?>> exportFutures = new LinkedList<ExportFuture<?>>();
        
        final boolean failOnError = true; //options != null ? options.isFailOnError() : true;
        
        for (final String typeId : typeIds) {
            final File typeDir = new File(directory, typeId);
            logger.info("Exporting all data of type {} to {}", typeId, typeDir);
            
            final Iterable<? extends IPortalData> dataForType = this.getPortalData(typeId);
            for (final IPortalData data : dataForType) {
                final String dataId = data.getDataId();

                //Check for completed futures on every iteration, needed to fail as fast as possible on an import exception
                waitForFutures(exportFutures, failOnError, false);
                
                //Submit the export task
                final Future<?> exportFuture = this.importExportThreadPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        exportData(typeId, dataId, typeDir);
                    }
                });
                
                //Add the future for tracking
                exportFutures.offer(new ExportFuture(exportFuture, typeId, dataId));
            }
        }
        
        waitForFutures(exportFutures, failOnError, true);
    }

    @Override
    public void exportAllData(File directory) {
        final Set<String> typeIds = new LinkedHashSet<String>();
        for (final IPortalDataType portalDataType : this.exportPortalDataTypes) {
            typeIds.add(portalDataType.getTypeId());
        }
        this.exportAllDataOfType(typeIds, directory);
    }

    protected IDataExporter<Object> getPortalDataExporter(String typeId) {
        final IDataExporter<Object> dataExporter = this.portalDataExporters.get(typeId);
        if (dataExporter == null) {
            throw new IllegalArgumentException("No IDataExporter exists for: " + typeId);   
        }
        return dataExporter;
    }

	@Override
	public void deleteData(String typeId, String dataId) {
		final IDataDeleter<Object> portalDataExporter = this.portalDataDeleters.get(typeId);
		final Object data = portalDataExporter.deleteData(dataId);
		if(data == null) {
			logger.info("portalDataExporter#deleteData returned null for typeId " + typeId + " and dataId " + dataId );
		}
	}
    
    /**
     * Used by batch import and export to wait for queued tasks to complete. Handles fail-fast behavior
     * if any of the tasks threw and exception by canceling all queued futures and logging a summary of
     * the failures. All completed futures are removed from the queue.
     * 
     * @param futures Queued futures to check for completeness
     * @param failOnError If true and any {@link Future#get()} throws an exception all Futures in the Queue will be canceled and an exception will be thrown.
     * @param wait If true it will wait for all futures to complete, if false only check for completed futures
     */
    protected void waitForFutures(
            final Queue<? extends FutureHolder<?>> futures, final boolean failOnError, 
            final boolean wait) {
        
        List<FutureHolder<?>> failedFutures = null;
        
        for (Iterator<? extends FutureHolder<?>> futuresItr = futures.iterator(); futuresItr.hasNext();) {
            final FutureHolder<?> futureHolder = futuresItr.next();
             
            //If waiting, or if not waiting but the future is already done do the get
            final Future<?> future = futureHolder.getFuture();
            if (wait || failedFutures != null || (!wait && future.isDone())) {
                try {
                    //Don't bother doing a get() on cancelled futures
                    if (!future.isCancelled()) {
                        if (this.maxWait > 0) {
                            future.get(this.maxWait, this.maxWaitTimeUnit);
                        }
                        else {
                            future.get();
                        }
                    }
                    
                    futuresItr.remove();
                }
                catch (Exception e) {
                    futuresItr.remove();
                    
                    if (failOnError) {
                        //If this is the first exception reset the iterator to the start of the futures list
                        if (failedFutures == null) {
                            //Immediately try to cancel all queued future tasks to avoid creating more error noise than necessary
                            for (final FutureHolder<?> cancelFuture : futures) {
                                cancelFuture.getFuture().cancel(true);
                            }
                            
                            //Reset the iterator since we now need to wait for ALL futures to complete
                            futuresItr = futures.iterator();
                            
                            //Create List used to track failed imports
                            failedFutures = new LinkedList<FutureHolder<?>>();
                        }
                        
                        //Add resource to the list of failed tasks 
                        failedFutures.add(futureHolder);
                        
                        //Log the import error
                        this.logger.error("Exception while " + futureHolder, e);
                    }
                    else {
                        if (this.logger.isDebugEnabled()) {
                            this.logger.warn("Exception while '" + futureHolder + "', file will be ignored" , e);
                        }
                        else {
                            this.logger.warn("Exception while '{}', file will be ignored: {}", futureHolder, e.getCause().getMessage());
                        }
                    }
                }
            }
        }
        
        //If any of the Futures threw an exception report details and fail
        if (failedFutures != null) {
            final StringBuilder msg = new StringBuilder("Halted due to ").append(failedFutures.size()).append(" failures, see previous exceptions for causes.\n");
            for (final FutureHolder<?> failedResource : failedFutures) {
                msg.append("\t").append(failedResource.getDescription()).append("\n");
            }
             
            throw new RuntimeException(msg.toString());
        }
    }
    
    private static abstract class FutureHolder<T> {
        private final Future<T> future;

        public FutureHolder(Future<T> future) {
            this.future = future;
        }

        public Future<T> getFuture() {
            return this.future;
        }
        
        public abstract String getDescription();
    }
    
    private static class ImportFuture<T> extends FutureHolder<T> {
        private final Resource resource;

        public ImportFuture(Future<T> future, Resource resource) {
            super(future);
            this.resource = resource;
        }

        public Resource getResource() {
            return this.resource;
        }
        
        @Override
        public String getDescription() {
            return this.resource.getDescription();
        }

        @Override
        public String toString() {
            return "importing " + this.getDescription();
        }
    }
    
    private static class ExportFuture<T> extends FutureHolder<T> {
        private final String typeId;
        private final String dataId;

        public ExportFuture(Future<T> future, String typeId, String dataId) {
            super(future);
            this.typeId = typeId;
            this.dataId = dataId;
        }

        public String getTypeId() {
            return this.typeId;
        }

        public String getDataId() {
            return this.dataId;
        }
        
        @Override
        public String getDescription() {
            return "type=" + this.typeId + ", dataId=" + this.dataId;
        }

        @Override
        public String toString() {
            return "exporting " + this.getDescription();
        }
    }
}
