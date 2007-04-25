package org.cdlib.xtf.lazyTree;

/**
 * Copyright (c) 2004, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution.
 * - Neither the name of the University of California nor the names of its
 *   contributors may be used to endorse or promote products derived from this 
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.FileNotFoundException;
import java.io.IOException;

import net.sf.saxon.Configuration;
import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.om.NamePool;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.type.Type;

import org.cdlib.xtf.lazyTree.hackedSaxon.TinyBuilder;
import org.cdlib.xtf.lazyTree.hackedSaxon.TinyNodeImpl;
import org.cdlib.xtf.lazyTree.hackedSaxon.TinyTree;
import org.cdlib.xtf.util.ConsecutiveMap;
import org.cdlib.xtf.util.IntegerValues;
import org.cdlib.xtf.util.PackedByteBuf;
import org.cdlib.xtf.util.StructuredStore;
import org.cdlib.xtf.util.SubStoreWriter;
import org.cdlib.xtf.util.XTFSaxonErrorListener;

/**
 * <p>Creates and/or loads a disk-based representation of an XML tree. Once 
 * created, the persistent version can be quickly and incrementally loaded
 * into memory.</p>
 * 
 * <p>To build a tree, call the {@link #begin(StructuredStore)} method to start the 
 * process. Using the Receiver it returns, pass all the SAX events gathered 
 * from parsing the document. Finally, {@link #finish(Receiver, boolean)} will 
 * complete the process.</p>
 * 
 * <p>To load a tree that was built previously, use either load method:
 * {@link #load(StructuredStore)} or {@link #load(StructuredStore, LazyDocument)}.
 * 
 * @author Martin Haye
 */
public class LazyTreeBuilder
{
    /** The Saxon 'tiny' document, used to load the input tree */
    private TinyTree         tree;
    
    /** Name pool used to map namecodes */
    private NamePool         namePool;
    
    /** Mapping of names found to our internal name numbers */
    private ConsecutiveMap   names = new ConsecutiveMap();
    
    /** Saxon configuration used for tree loading */
    private Configuration    config;
    
    /** Pipeline configuration */
    private PipelineConfiguration pipe;

    
    /** File version stored in the persistent file. */
    public static final String curVersion = "1.01";
    
    /** Default constructor -- sets up the configuration */
    public LazyTreeBuilder()
    {
        config = new Configuration();
        config.setErrorListener( new XTFSaxonErrorListener() );
        pipe = new PipelineConfiguration();
        pipe.setConfiguration( config );
        pipe.setErrorListener( config.getErrorListener() );
    }
    
    /** Establishes the name pool used to resolve namecodes */
    public void setNamePool( NamePool pool ) {
        namePool = pool;
    }
    
    /**
     * Load a persistent document using the default loader.
     * 
     * @param treeStore   The store to load from
     * 
     * @return The root node of the document (which implements DocumentInfo)
     */
    public NodeInfo load( StructuredStore treeStore )
        throws FileNotFoundException, IOException
    {
        LazyDocument targetDoc = new LazyDocument();
        load( treeStore, targetDoc );
        return targetDoc;
    } // load()

    
    /**
     * Load a pre-existing persistent tree and load it into an empty in-memory
     * document.
     * 
     * @param treeStore     The store to load from
     * @param emptyDoc      An empty document object to initialize
     */
    public void load( StructuredStore treeStore, LazyDocument emptyDoc )
        throws FileNotFoundException, IOException
    {
        // Don't use it if the version number is old.
        String fileVer = treeStore.getUserVersion();
        if( fileVer.compareTo(curVersion) < 0 )
            throw new IOException( "Cannot use old version of LazyTree file" ); 
        
        // Now init the document (which loads the root node.)
        emptyDoc.init( namePool, treeStore );
        emptyDoc.setSystemId( treeStore.getSystemId() );
    }
    
    /**
     * Alternate way of constructing a lazy tree. First, begin() is called,
     * returning a Receiver that should receive all the SAX events from the
     * input. When all events have been sent, then call 
     * {@link #finish(Receiver, boolean)}.
     */
    public Receiver begin( StructuredStore treeStore )
        throws IOException
    {
        // A great way to read the tree in just the form we need it is to
        // use Saxon's "TinyTree" implementation. Unfortunately, all of its
        // members are private, so we actually use a hacked version whose only
        // difference is that they're made public, and that text is accumulated
        // straight to the disk file, rather than to a memory buffer.
        //
        TinyBuilder builder = new TinyBuilder();
        if( namePool == null ) 
            namePool = NamePool.getDefaultNamePool();
        builder.setPipelineConfiguration( pipe );

        // We're going to make a structured file to contain the entire tree.
        // To save memory, we'll write the character data directly to it
        // rather than buffer it up.
        //
        builder.setTreeStore( treeStore );
        
        treeStore.setUserVersion( curVersion );
        
        SubStoreWriter textFile = treeStore.createSubStore( "text" );
        builder.setTextStore( textFile );
        
        // Done for now.
        return builder;
        
    } // begin()
    
    /**
     * Retrieves the current node number in the build. Indexer uses this to
     * record node numbers in text chunks.
     * 
     * @param inBuilder     The builder gotten from begin()
     * @return              The current node number.
     */
    public int getNodeNum( Receiver inBuilder ) {
        TinyBuilder builder = (TinyBuilder)inBuilder;
        tree = builder.getTree();
        return tree.numberOfNodes;
    } // getNodeNum()

    /**
     * Completes writing out a disk-based file. Assumes that the receiver
     * (which must come from begin()) has been sent all the SAX events for 
     * the input document.
     */
    public void finish( Receiver inBuilder, boolean closeStore )
        throws IOException
    {
        TinyBuilder builder = (TinyBuilder)inBuilder;
        StructuredStore treeStore = builder.getTreeStore();
        
        tree = builder.getTree();
         
        // Done with the text file now.
        builder.getTextStore().close();
        
        // If the build failed, delete the file.
        if( tree == null ) {
            treeStore.close();
            treeStore.delete();
            return;
        }
         
        // Make sure we support all the features used in the document.
        checkSupport();
         
        // Now make a structured file containing the entire tree's contents.
        writeNames( treeStore.createSubStore("names") );
        writeAttrs( treeStore.createSubStore("attributes") ); // must be before nodes
        writeNodes( treeStore.createSubStore("nodes") );
        
        // Close the store if requested.
        if( closeStore )
            treeStore.close();
        
        // All done!
        tree      = null;
        names    = null;
    }
    
    /** 
     * Build and write out the table of names referenced by the tree. Also
     * includes the namespaces.
     * 
     * @param out   SubStore to write to.
     */
    private void writeNames( SubStoreWriter out )
        throws IOException
    {
        PackedByteBuf buf = new PackedByteBuf( 1000 );
        
        // Write out all the namespaces.
        buf.writeInt( tree.numberOfNamespaces );
        for( int i = 0; i < tree.numberOfNamespaces; i++ ) {
            int code = tree.namespaceCode[i];
            buf.writeString( namePool.getPrefixFromNamespaceCode(code) );
            buf.writeString( namePool.getURIFromNamespaceCode(code) );
            buf.writeInt( tree.namespaceParent[i] );
        }
        
        // Add all the namecodes from elements and attributes.
        for( int i = 0; i < tree.numberOfNodes; i++ ) {
            if( tree.nameCode[i] >= 0 )
                names.put( IntegerValues.valueOf(tree.nameCode[i]) );
        }
        
        for( int i = 0; i < tree.numberOfAttributes; i++ ) {  
            if( tree.attCode[i] >= 0 )
                names.put( IntegerValues.valueOf(tree.attCode[i]) );
        }
        
        // Write out all the namecodes.
        Object[] nameArray = names.getArray();
        buf.writeInt( nameArray.length );
        for( int i = 0; i < nameArray.length; i++ ) {
            int code = ((Integer)nameArray[i]).intValue();
            buf.writeString( namePool.getPrefix(code) );
            buf.writeString( namePool.getURI(code) );
            buf.writeString( namePool.getLocalName(code) );
        }
        
        // All done.
        buf.output( out );
        out.close();
    } // writeNames()
    
    /** 
     * Build and write out all the nodes in the tree. The resulting table has
     * fixed-sized entries, sized to fit the largest node.
     * 
     * @param out   SubStore to write to.
     */
    private void writeNodes( SubStoreWriter out )
    throws IOException
    {
        // Write the root node's number
        out.writeInt( 0 ); 

        // Pack up each node. That way we can calculate the maximum size of
        // any particular one, and they can be randomly accessed by multiplying
        // the node number by that size.
        //
        PackedByteBuf[] nodeBufs = new PackedByteBuf[tree.numberOfNodes];
        int maxSize = 0;
        tree.ensurePriorIndex();
        for( int i = 0; i < tree.numberOfNodes; i++ ) {
            PackedByteBuf buf = nodeBufs[i] = new PackedByteBuf( 20 );
            
            // FIXME: Kludge: We don't currently handle writing processing
            // instructions to the lazy file, so for now it seems to work
            // just replacing them with an empty element.
            //
            if (tree.nodeKind[i] == Type.PROCESSING_INSTRUCTION) {
              tree.nodeKind[i] = Type.ELEMENT;
              tree.alpha[i] = -1;
              tree.beta[i] = -1;
            }
            
            // Kind
            buf.writeByte( tree.nodeKind[i] );
            
            // Flag
            NodeInfo node     = tree.getNode( i );
            int      nameCode = tree.nameCode[i];
            int      parent   = (node.getParent() != null) ?
                                (((TinyNodeImpl)node.getParent()).nodeNr) :
                                 -1;
            int      prevSib  = tree.prior[i];
            int      nextSib  = (tree.next[i] > i) ? tree.next[i] : -1;
            int      child    = node.hasChildNodes() ? (i+1) : -1;
            int      alpha    = tree.alpha[i];
            int      beta     = tree.beta[i];

            int flags = ((nameCode != -1) ? Flag.HAS_NAMECODE     : 0) |
                        ((parent   != -1) ? Flag.HAS_PARENT       : 0) |
                        ((prevSib  != -1) ? Flag.HAS_PREV_SIBLING : 0) |
                        ((nextSib  != -1) ? Flag.HAS_NEXT_SIBLING : 0) |
                        ((child    != -1) ? Flag.HAS_CHILD        : 0) |
                        ((alpha    != -1) ? Flag.HAS_ALPHA        : 0) |
                        ((beta     != -1) ? Flag.HAS_BETA         : 0);
            buf.writeInt( flags );
            
            // Name code
            if( nameCode >= 0 ) {
                int nameIdx = names.get( IntegerValues.valueOf(nameCode) );
                assert nameIdx >= 0 : "A name was missed when writing name codes";
                buf.writeInt( nameIdx );
            }
            
            // Parent
            if( parent >= 0 )
                buf.writeInt( parent );
            
            // Prev sibling
            if( prevSib >= 0 )
                buf.writeInt( prevSib  );
            
            // Next sibling.
            if( nextSib >= 0 )
                buf.writeInt( nextSib );
            
            // First child (if any).
            if( child >= 0 ) {
                assert child != 0;
                buf.writeInt( child );
            }
            
            // Alpha and beta
            if( alpha != -1 )
                buf.writeInt( tree.alpha[i] );
            if( beta != -1 )
                buf.writeInt( tree.beta[i] );
            
            // Now calculate the size of the buffer, and bump the max if needed
            buf.compact();
            maxSize = Math.max( maxSize, buf.length() );
        } // for i
        
        // Okay, we're ready to write out the node table now. First comes the
        // number of nodes, followed by the size in bytes of each one.
        //
        out.writeInt( tree.numberOfNodes );
        out.writeInt( maxSize );
        
        for( int i = 0; i < tree.numberOfNodes; i++ )
            nodeBufs[i].output( out, maxSize );
        
        // All done.
        out.close();
    } // writeNodes()   
    
    /** 
     * Build and write out all the attributes in the tree. The resulting table
     * has variable-sized entries. 
     * 
     * @param out   SubStore to write to.
     */
    private void writeAttrs( SubStoreWriter out )
        throws IOException
    {
        // Do a dry run to figure out the max size of any entry.
        int maxSize = 0;
        PackedByteBuf buf = new PackedByteBuf( 100 );
        for( int i = 0; i < tree.numberOfAttributes; ) 
        {
            // Figure out how many attributes for this parent.
            int j;
            for( j = i+1; j < tree.numberOfAttributes; j++ ) {
                if( tree.attParent[j] != tree.attParent[i] )
                    break;
            }
            
            int nAttrs = j - i;
            
            // Pack them all up.
            buf.reset();
            buf.writeInt( nAttrs );
            for( j = i; j < i+nAttrs; j++ ) {
            
                // Name code
                int nameIdx = names.get( IntegerValues.valueOf(tree.attCode[j]) );
                assert nameIdx >= 0 : "A name was missed when writing name codes";
                buf.writeInt( nameIdx );
                
                // Value
                buf.writeString( tree.attValue[j].toString() );
            }
            
            // Bump the max if necessary.
            maxSize = Math.max( maxSize, buf.length() );
            
            // Next!
            i += nAttrs;
        } // for i
        
        // Write the max size of any block.
        out.writeInt( maxSize );
        
        // Write out each attrib, and record their offsets and lengths.
        for( int i = 0; i < tree.numberOfAttributes; ) 
        {
            // Figure out how many attributes for this parent.
            int j;
            for( j = i+1; j < tree.numberOfAttributes; j++ ) {
                if( tree.attParent[j] != tree.attParent[i] )
                    break;
            }
            
            int nAttrs = j - i;
            
            // Pack them all up.
            buf.reset();
            buf.writeInt( nAttrs );
            for( j = i; j < i+nAttrs; j++ ) {
            
                // Name code
                int nameIdx = names.get( IntegerValues.valueOf(tree.attCode[j]) );
                assert nameIdx >= 0 : "A name was missed when writing name codes";
                buf.writeInt( nameIdx );
                
                // Value
                buf.writeString( tree.attValue[j].toString() );
            }
            
            // Record the offset in the attribute file.
            int parent = tree.attParent[i];
            assert tree.nodeKind[parent] == Type.ELEMENT;
            tree.alpha[parent] = (int) out.length();
            
            // Write out the data.
            buf.output( out );
            
            // Next!
            i += nAttrs;
        } // for i
        
        // To avoid the reader having to worry about overrunning the subfile,
        // write an extra maxSize bytes.
        //
        byte[] tmp = new byte[maxSize];
        out.write( tmp );
        
        // All done.
        out.close();
    } // writeAttrs()   
    
    /**
     * Checks that the tree doesn't use features we don't support. If it does,
     * we throw an exception.
     */
    private void checkSupport()
        throws IOException
    {
        if( tree.attTypeCode != null )
            throw new IOException( "LazyTree does not support attribute type annotations yet" );
    } // checkSupport()
     
} // class LazyTreeBuilder
