package org.cdlib.xtf.textEngine;

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

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.Vector;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;

import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.tree.TreeBuilder;

import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.chunk.SpanChunkedNotQuery;
import org.apache.lucene.chunk.SpanDechunkingQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.mark.SpanDocument;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanOrQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanRangeQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.search.spans.SpanWildcardQuery;

import org.cdlib.xtf.textIndexer.XTFTextAnalyzer;
import org.cdlib.xtf.util.Attrib;
import org.cdlib.xtf.util.AttribList;
import org.cdlib.xtf.util.EasyNode;
import org.cdlib.xtf.util.GeneralException;
import org.cdlib.xtf.util.Path;
import org.cdlib.xtf.util.Trace;
import org.cdlib.xtf.util.XMLFormatter;
import org.cdlib.xtf.util.XMLWriter;
import org.cdlib.xtf.util.XTFSaxonErrorListener;

/**
 * Processes URL parameters into a Lucene query, using a stylesheet to perform
 * the heavy lifting.
 * 
 * @author Martin Haye
 */
public class QueryRequest implements Cloneable
{
    /** Path (base dir relative) for the resultFormatter stylesheet */
    public String     displayStyle;
    
    /** Document rank to start with (0-based) */
    public int        startDoc     = 0;
    
    /** Max # documents to return from this query */
    public int        maxDocs      = 10;
    
    /** Path to the Lucene index we want to search */
    public String     indexPath;

    /** The Lucene query to perform */
    public Query      query;
    
    /** Optional list of fields to sort documents by */
    public String     sortMetaFields;

    /** Target size, in characters, for snippets */
    public int        maxContext   = 80;
    
    /** Limit on the total number of terms allowed */
    public int        termLimit    =  50;
    
    /** Limit on the total amount of "work" */
    public int        workLimit    =   0;
    
    /** Term marking mode */
    public int        termMode     = SpanDocument.MARK_SPAN_TERMS;
    
    /** 
     * During tokenization, the '*' wildcard has to be changed to a word
     * to keep it from being removed.
     */
    private static final String SAVE_WILD_STAR = "jwxbkn";

    /** 
     * During tokenization, the '?' wildcard has to be changed to a word
     * to keep it from being removed.
     */
    private static final String SAVE_WILD_QMARK   = "vkyqxw";

    /** 
     * Keeps track of the servlet base directory, used to map relative
     * file paths.
     */
    private File        baseDir;
    
    /**
     * Produce a Lucene query using a list of attributes (which are typically
     * gathered from the URL parameters to a servlet.) They are processed
     * through the specified stylesheet.
     * 
     * @param stylesheet Transformer that will be used. Should
     *                   already be stuffed with any global user-specified
     *                   parameters.
     * @param atts       Attributes to pass to the stylesheet, in plain and tokenized
     *                   form.
     * @param baseDir    Directory that paths are interpreted relative to.   
     */
    public QueryRequest( Transformer stylesheet, 
                         AttribList  atts,
                         File        baseDir )
        throws QueryGenException, TransformerException, QueryFormatError
    {
        this.baseDir = baseDir;
        
        NodeInfo    input  = tokenizeParams( atts );
        TreeBuilder output = new TreeBuilder();
        
        if( Trace.getOutputLevel() >= Trace.debug ) {
            Trace.debug( "*** queryParser input ***" );
            Trace.debug( XMLWriter.toString(input) );
        }
        
        // Make sure errors get directed to the right place.
        if( !(stylesheet.getErrorListener() instanceof XTFSaxonErrorListener) )
            stylesheet.setErrorListener( new XTFSaxonErrorListener() );

        stylesheet.transform( input, output );
        
        if( Trace.getOutputLevel() >= Trace.debug ) {
            Trace.debug( "*** queryParser output ***" );
            Trace.debug( XMLWriter.toString(output.getCurrentRoot()) );
        }
        
        parseOutputTop( new EasyNode(output.getCurrentRoot()) );
    } // constructor
    
    
    /**
     * Produce a Lucene query from the intermediate format that is normally
     * produced by the formatting stylesheet.
     * 
     * @param queryDoc A document containing the query.
     */
    public QueryRequest( NodeInfo queryDoc,
                         File     baseDir )
        throws QueryGenException, QueryFormatError
    {
        this.baseDir = baseDir;
        
        if( Trace.getOutputLevel() >= Trace.debug ) {
            Trace.debug( "*** query request ***" );
            Trace.debug( XMLWriter.toString(queryDoc) );
        }

        parseOutputTop( new EasyNode(queryDoc) );
    } // constructor
    
    
    // Creates an exact copy of this query request.
    public Object clone() 
    {
        try { return super.clone(); }
        catch( CloneNotSupportedException e ) { throw new RuntimeException(e); }
    } // clone()
    
    
    /**
     * Creates a document containing tokenized and untokenized versions of each
     * parameter.
     */
    public static NodeInfo tokenizeParams( AttribList atts )
        throws QueryGenException
    {
        XMLFormatter fmt = new XMLFormatter();
        
        // The top-level node marks the fact that this is the parameter list.
        fmt.beginTag( "parameters" );
        
        // Add each parameter to the document.
        for( Iterator iter = atts.iterator(); iter.hasNext(); ) {
            Attrib att = (Attrib) iter.next();
            
            // Don't tokenize the servlet path! It's built-in, not part
            // of the URL.
            //
            if( att.key.equals("servlet.path") )
                continue;
            addParam( fmt, att.key, att.value );
        }
        
        fmt.endTag();
        
        // And we're done.
        return fmt.toNode();
    } // buildInput()
    
    
    /**
     * Adds the tokenized and un-tokenized version of the attribute to the
     * given formatter.
     * 
     * @param fmt formatter to add to
     * @param name Name of the URL parameter
     * @param val String value of the URL parameter
     */
    private static void addParam( XMLFormatter fmt,
                                  String name, String val )
    {
        // Create the parameter node and assign its name and value.
        fmt.beginTag( "param" );
        fmt.attr( "name", name );
        fmt.attr( "value", val );
        
        // Now tokenize it.
        tokenize( fmt, val );
        
        // All done.
        fmt.endTag();
    } // addParam()
    
    
    /**
     * Break 'val' up into its component tokens and add elements for them.
     * 
     * @param fmt formatter to add to
     * @param val value to tokenize
     */
    private static void tokenize( XMLFormatter fmt, String val )
    {
        char[] chars   = val.toCharArray();
        char   inQuote = 0;
        String tmpStr;
        
        int i;
        int start = 0;
        for( i = 0; i < chars.length; i++ ) {
            char c = chars[i];
            
            if( c == inQuote ) {
                if( i > start ) {
                    tmpStr = new String( chars, start, i-start );
                    addTokens( inQuote, fmt, tmpStr );
                }
                inQuote = 0;
                start = i+1;
            }
            else if( inQuote == 0 && c == '\"' ) {
                if( i > start ) {
                    tmpStr = new String( chars, start, i-start );
                    addTokens( inQuote, fmt, tmpStr );
                }
                inQuote = c;
                start = i+1;
            }
            else
                ; // Don't change start... has result of building up a token.
        } // for i
        
        // Process the last tokens
        if( i > start ) {
            tmpStr = new String( chars, start, i-start );
            addTokens( inQuote, fmt, tmpStr ); 
        }
    } // tokenize()
    
    
    /**
     * Adds one or more token elements to a parameter node. Also handles
     * phrase nodes.
     * 
     * @param inQuote Non-zero means this is a quoted phrase, in which case the
     *                element will be 'phrase' instead of 'token', and it will
     *                be given sub-token elements.
     * @param fmt formatter to add to
     * @param str The token value
     */
    private static void addTokens( char         inQuote,  
                                   XMLFormatter fmt,
                                   String       str )
    {
        // If this is a quoted phrase, tokenize the words within it.
        if( inQuote != 0 ) {
            fmt.beginTag( "phrase" );
            fmt.attr( "value", str );
            tokenize( fmt, str );
            fmt.endTag();
            return;
        }
        
        // We want to retain wildcard characters, but the tokenizer won't see
        // them as part of a word. So substitute, temporarily.
        //
        str = saveWildcards( str );
        
        // Otherwise, use a tokenizer to break up the string.
        try {
            XTFTextAnalyzer analyzer = new XTFTextAnalyzer( null, -1 );
            TokenStream toks = analyzer.tokenStream( "text", new StringReader(str) );
            int prevEnd = 0;
            while( true ) {
                Token tok = toks.next();
                if( tok == null )
                    break;
                if( tok.startOffset() > prevEnd )
                    addToken( fmt, 
                              str.substring(prevEnd, tok.startOffset()),
                              false );
                prevEnd = tok.endOffset();
                addToken( fmt, tok.termText(), true );
            }
            if( str.length() > prevEnd )
                addToken( fmt, str.substring(prevEnd, str.length()),
                          false );
        }
        catch( IOException e ) {
            assert false : "How can analyzer throw IO error on string buffer?";
        }
    } // addToken()
    
    
    /**
     * Adds a token element to a parameter node.
     * 
     * @param fmt formatter to add to
     * @param str The token value
     * @param isWord true if  token is a real word, false if only punctuation
     */
    private static void addToken( XMLFormatter fmt,
                                  String str,
                                  boolean isWord )
    {
        // Remove spaces. If nothing is left, don't bother making a token.
        str = str.trim();
        if( str.length() == 0 )
            return;
        
        // Recover wildcards that were saved.
        str = restoreWildcards( str );
        
        // And create the node
        fmt.beginTag( "token" );
        fmt.attr( "value", str );
        fmt.attr( "isWord", isWord ? "yes" : "no" );
        fmt.endTag();
    } // addToken()
    
    
    /**
     * Converts wildcard characters into word-looking bits that would never
     * occur in real text, so the standard tokenizer will keep them part of
     * words. Resurrect using {@link #restoreWildcards(String)}.
     */
    private static String saveWildcards( String s )
    {
        // Early out if no wildcards found.
        if( s.indexOf('*') < 0 && s.indexOf('?') < 0 )
            return s;
        
        // Convert to wordish stuff.
        s = s.replaceAll( "\\*", SAVE_WILD_STAR );
        s = s.replaceAll( "\\?", SAVE_WILD_QMARK );
        return s;
    } // saveWildcards()
    
    
    /**
     * Restores wildcards saved by {@link #saveWildcards(String)}.
     */
    private static String restoreWildcards( String s )
    {
        // Early out if no wildcards found.
        if( s.indexOf(SAVE_WILD_STAR) < 0 && s.indexOf(SAVE_WILD_QMARK) < 0 )
            return s;

        // Convert back from wordish stuff to real wildcards.
        s = s.replaceAll( SAVE_WILD_STAR, "*" );
        s = s.replaceAll( SAVE_WILD_QMARK,   "?" );
        return s;
    } // restoreWildcards()
    
    
    /**
     * Convenience function to throw a {@link QueryGenException} with the 
     * given message.
     */
    private void error( String message )
        throws QueryGenException
    {
        throw new QueryGenException( message );
    } // error()
    
    
    /**
     * Processes the output of the generator stylesheet, turning it into a
     * Lucene query.
     * 
     * @param output The stylesheet output, whose first (and only) child
     *               should be a 'query' element.
     * @return The resulting Lucene query
     */
    private void parseOutputTop( EasyNode output )
        throws QueryGenException, QueryFormatError
    {
        if( "query".equals(output.name()) || "error".equals(output.name()) ) {
            parseOutput( output );
            return;
        }
        
        for( int i = 0; i < output.nChildren(); i++ ) {
            EasyNode main = output.child( i );
            String   name = main.name();
            
            if( !name.equals("query") && !name.equals("error") )
                error( "Expected 'query' or 'error' element at " +
                       "top level; found '" + name + "'" );
            
            parseOutput( main );
        }
    } // parseOutput()
    
    /**
     * Processes the main query node, turning it into a Lucene query.
     * 
     * @param main The 'query' element
     */
    private void parseOutput( EasyNode main )
    {
        if( main.name().equals("error") )
            throw new QueryFormatError( main.attrValue("message") );
        
        // Process all the top-level attributes.
        for( int i = 0; i < main.nAttrs(); i++ ) {
            String name = main.attrName( i );
            String val  = main.attrValue( i );
            parseMainAttrib( main, name, val );
        }

        // Process the children. If we find an old <combine> element,
        // traverse it just like a top-level query.
        //
        if( main.nChildren() != 1 ) {
            error( "<" + main.name() + "> element must have " +
                   " exactly one child element" );
        }
        
        for( int i = 0; i < main.nChildren(); i++ ) {
            EasyNode el = main.child( i );
            query = deChunk( parseQuery(el, null, Integer.MAX_VALUE) );
        }

        
        if( main.name().equals("query") &&
            Trace.getOutputLevel() >= Trace.debug )
        {
            Trace.debug( "Lucene query as parsed: " + query.toString() );
        }
        
        // Check that we got the required parameters.
        if( main.name().equals("query") ) {
            if( indexPath == null )
                error( "'indexPath' attribute missing from <query> element" );
        }
        
    } // parseOutput()
    
    /**
     * Parse an attribute on the main query element (or, for backward
     * compatability, on its immediate children.)
     * 
     * If the attribute isn't recognized, an error exception is thrown.
     */
    void parseMainAttrib( EasyNode el, String name, String val )
    {
        if( name.equals("style") ) {
            displayStyle = Path.resolveRelOrAbs(baseDir, val);
            if( !(new File(displayStyle).canRead()) &&
                !val.equals("NullStyle.xsl") ) 
            {
                error( "Style \"" + displayStyle + 
                       "\" specified in '" + name + "' element " +
                    
                "does not exist" );
            }
        }

        else if( name.equals("startDoc") ) {
            startDoc = parseIntAttrib( el, "startDoc" );
            
            // Adjust for 1-based start doc.
            startDoc = Math.max( 0, startDoc-1 );
        }
        
        else if( name.equals("maxDocs") )
            maxDocs = parseIntAttrib( el, "maxDocs" );
        
        else if( name.equals("indexPath") ) {
            indexPath = Path.resolveRelOrAbs(baseDir, val);
            if( !(new File(indexPath).exists()) )
                error( "Index path \"" + indexPath + 
                       "\" specified in '" + name + "' element " +
                       "does not exist" );
        }
        
        else if( name.equals("termLimit") )
            termLimit = parseIntAttrib( el, "termLimit" );
        
        else if( name.equals("workLimit") )
            workLimit = parseIntAttrib( el, "workLimit" );
        
        else if( name.equals("sortMetaFields") )
            sortMetaFields = val;
        
        else if( name.equals("maxContext") )
            maxContext = parseIntAttrib( el, "maxContext" );
        
        // Backward compatability.
        else if( name.equals("contextChars") )
            maxContext = parseIntAttrib( el, "contextChars" );
        
        else if( name.equals("termMode") ) {
            if( val.equalsIgnoreCase("none") )
                termMode = SpanDocument.MARK_NO_TERMS;
            else if( val.equalsIgnoreCase("hits") )
                termMode = SpanDocument.MARK_SPAN_TERMS;
            else if( val.equalsIgnoreCase("context") )
                termMode = SpanDocument.MARK_CONTEXT_TERMS;
            else if( val.equalsIgnoreCase("all") )
                termMode = SpanDocument.MARK_ALL_TERMS;
            else
                error( "Unknown value for 'termMode'; expecting " +
                       "'none', 'hits', 'context', or 'all'" ); 
        }
        
        else if( name.equals("field") || name.equals("metaField") )
            ; // handled elsewhere
        
        else if( name.equals("inclusive") &&
                 el.name().equals("range") )
            ; // handled elsewhere
        
        else if( name.equals("slop") &&
                 el.name().equals("near") )
            ; // handled elsewhere
        
        else {
            error( "Unrecognized attribute \"" + name + "\" " +
                   "on <" + el.name() + "> element" );
        }
    } // parseMainAttrib()

    /**
     * Locate the named attribute and retrieve its value as an integer.
     * If not found, an error exception is thrown.
     * 
     * @param el Element to search
     * @param attribName Attribute to find
     */
    private int parseIntAttrib( EasyNode el, String attribName )
        throws QueryGenException
    {
        return parseIntAttrib( el, attribName, false, 0 );
    }
    
    /**
     * Locate the named attribute and retrieve its value as an integer.
     * If not found, return a default value.
     * 
     * @param el EasyNode to search
     * @param attribName Attribute to find
     * @param defaultVal If not found and useDefault is true, return this 
     *                   value.
     */
    private int parseIntAttrib( EasyNode el, 
                                String attribName, 
                                int defaultVal  )
        throws QueryGenException
    {
        return parseIntAttrib( el, attribName, true, defaultVal );
    }
    
    /**
     * Locate the named attribute and retrieve its value as an integer.
     * Handles default processing if requested.
     * 
     * @param el EasyNode to search
     * @param attribName Attribute to find
     * @param useDefault true to supply a default value if none found,
     *                   false to throw an exception if not found.
     * @param defaultVal If not found and useDefault is true, return this 
     *                   value.
     */
    private int parseIntAttrib( EasyNode el, String attribName, 
                                boolean useDefault, int defaultVal )
        throws QueryGenException
    {
        String elName = el.name();
        String str = parseStringAttrib( el, 
                                        attribName,
                                        useDefault,
                                        null );
        if( str == null && useDefault )
            return defaultVal;
        
        try {
            return Integer.parseInt( str );
        } catch( Exception e ) {
            error( "'" + attribName + "' attribute of '" + elName + 
                   "' element is not a valid integer" );
            return 0;
        }
    } // parseIntAttrib()
    
    
    /**
     * Locate the named attribute and retrieve its value as a string. If
     * not found, an error exception is thrown.
     * 
     * @param el EasyNode to search
     * @param attribName Attribute to find
     */
    private String parseStringAttrib( EasyNode el, 
                                      String  attribName ) 
        throws QueryGenException
    {
        return parseStringAttrib( el, attribName, false, null );
    }
    
    /**
     * Locate the named attribute and retrieve its value as a string. If
     * not found, return a default value.
     * 
     * @param el EasyNode to search
     * @param attribName Attribute to find
     * @param defaultVal If not found, return this value.
     */
    private String parseStringAttrib( EasyNode el, 
                                      String  attribName,
                                      String  defaultVal ) 
        throws QueryGenException
    {
        return parseStringAttrib( el, attribName, true, defaultVal );
    }
    
    /**
     * Locate the named attribute and retrieve its value as a string.
     * Handles default processing if requested.
     * 
     * @param el EasyNode to search
     * @param attribName Attribute to find
     * @param useDefault true to supply a default value if none found,
     *                   false to throw an exception if not found.
     * @param defaultVal If not found and useDefault is true, return this 
     *                   value.
     */
    private String parseStringAttrib( EasyNode el, 
                                      String  attribName, 
                                      boolean useDefault,
                                      String  defaultVal )
        throws QueryGenException
    {
        String elName = el.name();
        String str = el.attrValue( attribName );

        if( str == null || str.length() == 0 ) {
            if( !useDefault )
                error( "'" + elName + "' element must specify '" + 
                       attribName + "' attribute" );
            return defaultVal;
        }
        
        return str;
        
    } // parseStringAttrib()
    
    
    /**
     * If the given element has a 'field' attribute, return its value;
     * otherwise return 'parentField'. Also checks that field cannot be
     * specified if parentField has already been.
     */
    private String parseField( EasyNode el, String parentField )
        throws QueryGenException
    {
        if( !el.hasAttr("metaField") && !el.hasAttr("field") )
            return parentField;
        String attVal = el.attrValue("field");
        if( attVal == null || attVal.length() == 0 )
            attVal = el.attrValue( "metaField" );
        
        if( attVal.length() == 0 )
            error( "'field' attribute cannot be empty" );
        if( attVal.equals("sectionType") )
            error( "'sectionType' is not valid for the 'field' attribute" );
        if( parentField != null && !parentField.equals(attVal) )
            error( "Cannot override ancestor 'field' attribute" );
        
        return attVal;
    }

    
    /**
     * Parse a 'sectionType' query element, if one is present. If not, 
     * simply returns null.
     */
    private SpanQuery parseSectionType( EasyNode parent, 
                                        String field,
                                        int maxSnippets )
        throws QueryGenException
    {
        // Find the sectionType element (if any)
        EasyNode sectionType = parent.child( "sectionType" );
        if( sectionType == null )
            return null;
        
        // These sectionType queries only belong in the "text" field.
        if( !(field.equals("text")) )
            error( "'sectionType' element is only appropriate in queries on the 'text' field" );
        
        // Make sure it only has one child.
        if( sectionType.nChildren() != 1 )
            error( "'sectionType' element requires exactly " +
                   "one child element" );
        
        return (SpanQuery) parseQuery( sectionType.child(0), 
                                       "sectionType", maxSnippets );
    } // parseSectionType()
    
    /**
     * Recursively parse a query.
     */
    private Query parseQuery( EasyNode parent, String field, int maxSnippets )
        throws QueryGenException
    {
        String name = parent.name();
        if( !name.matches(
                "^query$|^term$|^all$|^range$|^phrase$|^near$" +
                "|^and$|^or$|^not$" +
                "|^combine$|^meta$|^text$") ) // old stuff, for compatability
        {
            error( "Expected: 'query', 'term', 'all', 'range', 'phrase', " +
                   "'near', 'and', 'or', or 'not'; found '" + name + "'" );
        }
        
        // Old stuff, for compatability.
        if( name.equals("text") )
            field = "text";

        // 'not' queries are handled at the level above.
        assert( !name.equals("not") );
        
        // Default to no boost.
        float boost = 1.0f;
        
        // Validate all attributes.
        for( int i = 0; i < parent.nAttrs(); i++ ) {
            String attrName = parent.attrName( i );
            String attrVal  = parent.attrValue( i );
            
            if( attrName.equals("boost") ) {
                try {
                    boost = Float.parseFloat( attrVal );
                }
                catch( NumberFormatException e ) {
                    error( "Invalid float value \"" + attrVal + "\" for " +
                           "'boost' attribute" );
                }
            }
            else if( attrName.equals("maxSnippets") ) {
                maxSnippets = parseIntAttrib( parent, attrName );
                if( maxSnippets < 0 )
                    maxSnippets = 999999999;
            }
            else
                parseMainAttrib( parent, attrName, attrVal );
        }
        
        // Do the bulk of the parsing below...
        Query result = parseQuery2( parent, name, field, maxSnippets );
        
        // And set any boost that was specified.
        if( boost != 1.0f )
            result.setBoost( boost );
        
        // If a sectionType query was specified, add that to the mix.
        SpanQuery secType = parseSectionType( parent, field, maxSnippets );
        if( secType != null ) {
            SpanQuery combo = 
                 new SpanSectionTypeQuery( (SpanQuery)result, secType );
            combo.setSpanRecording( ((SpanQuery)result).getSpanRecording() );
            result = combo;
        }
        
        // All done!
        return result;
        
    } // parseQuery()
    
    /** 
     * Main work of recursively parsing a query. 
     */
    private Query parseQuery2( EasyNode parent, String name, String field,
                               int maxSnippets )
        throws QueryGenException
    {
        // Term query is the simplest kind.
        if( name.equals("term") ) {
            Term term = parseTerm( parent, field, "term" );
            SpanQuery q = isWildcardTerm(term) ? 
                new SpanWildcardQuery( term, termLimit ) :
                new SpanTermQuery( term );
            q.setSpanRecording( maxSnippets );
            return q;
        }
        
        // Get field name if specified.
        field = parseField( parent, field );
        
        // Range queries are also pretty simple.
        if( name.equals("range") )
            return parseRange( parent, field, maxSnippets );

        // For text queries, 'all', 'phrase', and 'near' can be viewed
        // as phrase queries with different slop values.
        //
        // 'all' means essentially infinite slop (limited to the actual
        //          chunk overlap at runtime.)
        // 'phrase' means zero slop
        // 'near' allows specifying the slop (again limited to the actual
        //          chunk overlap at runtime.)
        //
        if( name.equals("all") || name.equals("phrase") || name.equals("near"))
        {   
            int slop = name.equals("all") ? 999999999 :
                       name.equals("phrase") ? 0 :
                       parseIntAttrib( parent, "slop" );
            return makeProxQuery( parent, slop, field, maxSnippets );
        }
        
        // All other cases fall through to here: and, or. Use our special
        // de-duplicating span logic. First, get all the sub-queries.
        //
        Vector subVec = new Vector();
        Vector notVec = new Vector();
        for( int i = 0; i < parent.nChildren(); i++ ) {
            EasyNode el = parent.child( i );
            if( el.name().equals("sectionType") )
                ; // handled elsewhere
            else if( el.name().equals("not") ) { 
                Query q = parseQuery2(el, name, field, maxSnippets);
                if( q != null )
                    notVec.add( q );
            }
            else {
                Query q = parseQuery(el, field, maxSnippets);
                if( q != null )
                    subVec.add( q );
            }
        }
        
        // If no sub-queries, return an empty query.
        if( subVec.isEmpty() )
            return null;
        
        // If only one sub-query, just return that.
        if( subVec.size() == 1 && notVec.isEmpty() )
            return (Query) subVec.get(0);
        
        // Divide up the queries by field name.
        HashMap fieldQueries = new HashMap();
        for( int i = 0; i < subVec.size(); i++ ) {
            Query q = (Query) subVec.get(i);
            field = (q instanceof SpanQuery) ? 
                         ((SpanQuery)q).getField() : "<none>";
            if( !fieldQueries.containsKey(field) )
                fieldQueries.put( field, new Vector() );
            ((Vector)fieldQueries.get(field)).add( q );
        } // for i
        
        // Same with the "not" queries.
        HashMap fieldNots = new HashMap();
        for( int i = 0; i < notVec.size(); i++ ) {
            Query q = (Query) notVec.get(i);
            field = (q instanceof SpanQuery) ? 
                         ((SpanQuery)q).getField() : "<none>";
            if( !fieldNots.containsKey(field) )
                fieldNots.put( field, new Vector() );
            ((Vector)fieldNots.get(field)).add( q );
        } // for i
        
        // If we have only queries for the same field, our work is simple.
        if( fieldQueries.size() == 1 ) {
            Vector queries = (Vector) fieldQueries.values().iterator().next();
            Vector nots;
            if( fieldNots.isEmpty() )
                nots = new Vector();
            else {
                assert fieldNots.size() == 1 : "case not handled";
                nots = (Vector) fieldNots.values().iterator().next();
                assert nots.get(0) instanceof SpanQuery : "case not handled";
                String notField = ((SpanQuery)nots.get(0)).getField();
                String mainField = ((SpanQuery)queries.get(0)).getField();
                assert notField.equals(mainField) : "case not handled";
            }
            return processSpanJoin(name, queries, nots, maxSnippets);
        }
        
        // Now form a BooleanQuery containing grouped span queries where
        // appropriate.
        //
        BooleanQuery bq = new BooleanQuery();
        boolean require = !name.equals("or");
        TreeSet keySet = new TreeSet( fieldQueries.keySet() );
        for( Iterator i = keySet.iterator(); i.hasNext(); ) {
            field = (String) i.next();
            Vector queries = (Vector) fieldQueries.get( field );
            Vector nots = (Vector) fieldNots.get( field );
            if( nots == null )
                nots = new Vector();

            if( field.equals("<none>") ||
                (queries.size() == 1 && nots.isEmpty()) )
            {
                for( int j = 0; j < queries.size(); j++ )
                    bq.add( deChunk((Query)queries.get(j)), require, false );
                for( int j = 0; j < nots.size(); j++ )
                    bq.add( deChunk((Query)queries.get(j)), false, true );
                continue;
            }

            // Span query/queries. Join them into a single span query.
            SpanQuery sq = processSpanJoin(name, queries, nots, maxSnippets); 
            bq.add( deChunk(sq), require, false );
        } // for i

        // And we're done.
        return bq;        
    } // parseBoolean() 
        
    
    /**
     * Joins a number of span queries together using a span query.
     * 
     * @param name    'and', 'or', 'near', etc.
     * @param subVec  Vector of sub-clauses
     * @param notVec  Vector of not clauses (may be empty)
     * 
     * @return        A new Span query joining the sub-clauses.
     */
    private SpanQuery processSpanJoin( String name, Vector subVec, 
                                       Vector notVec, int maxSnippets )
    {
        SpanQuery[] subQueries = 
            (SpanQuery[]) subVec.toArray( new SpanQuery[0] ); 
        
        // Now make the top-level query.
        SpanQuery q;
        if( subQueries.length == 1 )
            q = subQueries[0];
        else if( !name.equals("or") ) {
            // We can't know the actual slop until the query is run against
            // an index (the slop will be equal to max proximity). So set
            // it to a big value for now, and it will be clamped by
            // fixupSlop() later whent he query is run.
            //
            q = new SpanNearQuery( subQueries, 999999999, false );
        }
        else
            q = new SpanOrQuery( subQueries );

        q.setSpanRecording( maxSnippets );
        
        // Finish up by handling any not clauses found.
        return processTextNots( q, notVec, maxSnippets );
        
    } // processSpanJoin()

    /**
     * Ensures that the given query, if it is a span query on the "text"
     * field, is wrapped by a de-chunking query.
     */
    private Query deChunk( Query q )
    {
        // We only need to de-chunk span queries, not other queries.
        if( !(q instanceof SpanQuery) )
            return q;
        
        // Furthermore, we only need to de-chunk queries on the "text"
        // field.
        //
        SpanQuery sq = (SpanQuery) q;
        if( !sq.getField().equals("text") )
            return q;
        
        // If it's already de-chunked, no need to do it again.
        if( sq instanceof SpanDechunkingQuery )
            return q;
        
        // Okay, wrap it.
        SpanDechunkingQuery dq = new SpanDechunkingQuery( sq );
        dq.setSpanRecording( sq.getSpanRecording() );
        return dq;
        
    } // deChunk()  
      
    /** Determines if the term contains a wildcard character ('*' or '?') */
    private boolean isWildcardTerm( Term term )
    {
        if( term.text().indexOf('*') >= 0 )
            return true;
        if( term.text().indexOf('?') >= 0 )
            return true;
        return false;
    } // isWildcardTerm()

    /**
     * Parse a range query.
     */
    private Query parseRange( EasyNode parent, String field, int maxSnippets )
        throws QueryGenException
    {
        // Inclusive or exclusive?
        boolean inclusive = false;
        String yesno = parseStringAttrib( parent, "inclusive", "yes" );
        if( yesno.equals("yes") )
            inclusive = true;
        else if( !yesno.equals("no") )
            error( "'inclusive' attribute for 'range' query must have value " +
                   "'yes' or 'no'" );
        
        // Check the children for the lower and upper bounds.
        Term lower = null;
        Term upper = null;
        for( int i = 0; i < parent.nChildren(); i++ ) {
            EasyNode child = parent.child( i );
            String name = child.name();
            if( name.equals("lower") ) {
                if( lower != null )
                    error( "'lower' only allowed once as child of 'range' element" );
                lower = parseTerm( child, field, "lower" );
            }
            else if( name.equals("upper") ) {
                if( upper != null )
                    error( "'upper' only allowed once as child of 'range' element" );
                upper = parseTerm( child, field, "upper" );
            }
            else
                error( "'range' element may only have 'lower' and/or 'upper' " +
                       "as child elements" );
        } // for iter
        
        // Upper, lower, or both must be specified.
        if( lower == null && upper == null )
            error( "'range' element must have 'lower' and/or 'upper' child element(s)" );
        
        // And we're done.
        SpanQuery q = new SpanRangeQuery( lower, upper, inclusive, termLimit );
        q.setSpanRecording( maxSnippets );
        return q;
    } // parseRange()

    /**
     * If any 'not' clauses are present, this builds a query that filters them
     * out of the main query.
     */
    SpanQuery processTextNots( SpanQuery query, Vector notClauses,
                               int maxSnippets ) 
    {
        // If there aren't any 'not' clauses, we're done.
        if( notClauses.isEmpty() )
            return query;
        
        // If there's only one, the sub-query is simple.
        SpanQuery subQuery;
        if( notClauses.size() == 1 )
            subQuery = (SpanQuery) notClauses.get( 0 );
        else 
        {
            // Otherwise, 'or' all the nots together.
            SpanQuery[] subs = (SpanQuery[]) 
                notClauses.toArray( new SpanQuery[0] );
            subQuery = new SpanOrQuery( subs );
            subQuery.setSpanRecording( maxSnippets );
        }
        
        // Now make the final 'not' query. Note that the actual slop will have
        // to be fixed when the query is run.
        //
        SpanQuery nq = new SpanChunkedNotQuery( query, subQuery, 999999999 );
        nq.setSpanRecording( maxSnippets );
        return nq;
    } // processTextNots();
    
    
    /**
     * Generate a proximity query on a field. This uses the de-duplicating span
     * system.
     * 
     * @param parent The element containing the field name and terms.
     */
    Query makeProxQuery( EasyNode parent, int slop, String field,
                         int maxSnippets )
        throws QueryGenException
    {
        Vector terms  = new Vector();
        Vector notVec = new Vector();
        for( int i = 0; i < parent.nChildren(); i++ ) {
            EasyNode el = parent.child( i );
            if( el.name().equals("not") ) {
                if( slop == 0 )
                    error( "'not' clauses aren't supported in phrase queries" );
                
                // Make sure to avoid adding the 'not' terms to the term map,
                // since it would be silly to hilight them.
                //
                notVec.add( parseQuery(el, field, maxSnippets) );
            }
            else {
                SpanQuery q;
                if( slop == 0 ) {
                    Term t = parseTerm( el, field, "term" );
                    if( isWildcardTerm(t) )
                        q = new SpanWildcardQuery(t, termLimit);
                    else
                        q = new SpanTermQuery(t);
                    q.setSpanRecording( maxSnippets );
                    terms.add( q );
                }
                else
                    terms.add( parseQuery(el, field, maxSnippets) );
            }
        }
        
        if( terms.size() == 0 )
            error( "'" + parent.name() + "' element requires at " +
                   "least one term" );
        
        // Optimization: treat a single-term 'all' query as just a simple
        // term query.
        //
        if( terms.size() == 1 )
            return (SpanQuery) terms.elementAt(0);
        
        // Make a 'near' query out of it. Zero slop implies in-order.
        boolean inOrder = (slop == 0);
        SpanQuery q = new SpanNearQuery( 
                                  (SpanQuery[]) terms.toArray(new SpanQuery[0]), 
                                  slop,
                                  inOrder );
        q.setSpanRecording( maxSnippets );
        
        // And we're done.
        return q;
        
    } // makeTextAllQuery()
    
    
    /**
     * Parses a 'term' element. If not so marked, an exception is thrown.
     * 
     * @param parent The element to parse
     */
    private Term parseTerm( EasyNode parent, String field, String expectedName )
        throws QueryGenException
    {
        // Get field name if specified.
        field = parseField( parent, field );
        if( field == null )
            error( "'term' element requires 'field' attribute on " +
                   "itself or an ancestor" );
        
        if( !parent.name().equals(expectedName) )
            error( "Expected '" + expectedName + "' as child of '" + 
                   parent.parent().name() +
                   "' element, but found '" + parent.name() + "'" );
        
        String termText  = getText( parent );
        
        // For now, convert text to lowercase. In the future, we might allow
        // case-sensitive searching.
        //
        termText = termText.toLowerCase();
        
        // Make a term out of the field and the text.
        Term term = new Term( field, termText );
        
        return term;
        
    } // parseTerm()
    
    
    /**
     * Ensures that the element has only a single child node (ignoring
     * attributes), and that it's a text node.
     * 
     * @param el The element to get the text of
     * @return The string value of the text
     */
    private String getText( EasyNode el )
        throws QueryGenException
    {
        // There should be no element children, only text.
        int count = 0;
        String text = null;
        for( int i = 0; i < el.nChildren(); i++ ) {
            EasyNode n = el.child(i);
            if( !n.isElement() && !n.isText() )
            {
                count = -1;
                break;
            }
            if( n.isText() )
                text = n.toString();
            count++;
        }
        
        if( count != 1 )
            error( "A single text node is required for the '" +
                   el.name() + "' element" );
        
        return text;
    } // getText()
    
    /**
     * Exception class used to report errors from the query generator.
     */
    public class QueryFormatError extends GeneralException
    {
        public QueryFormatError( String message ) {
            super( message );
        }
        
        public boolean isSevere() { return false; }
    } // class QueryFormatError
    
} // class QueryRequest
