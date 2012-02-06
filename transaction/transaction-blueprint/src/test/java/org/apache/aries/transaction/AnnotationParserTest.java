package org.apache.aries.transaction;

import java.util.Arrays;

import org.apache.aries.blueprint.ComponentDefinitionRegistry;
import org.apache.aries.blueprint.Interceptor;
import org.apache.aries.blueprint.mutable.MutableBeanMetadata;
import org.apache.aries.blueprint.reflect.BeanMetadataImpl;
import org.apache.aries.transaction.parsing.AnnotationParser;
import org.apache.aries.transaction.pojo.AnnotatedPojo;
import org.apache.aries.transaction.pojo.BadlyAnnotatedPojo1;
import org.apache.aries.transaction.pojo.BadlyAnnotatedPojo2;
import org.apache.aries.unittest.mocks.MethodCall;
import org.apache.aries.unittest.mocks.MethodCallHandler;
import org.apache.aries.unittest.mocks.Skeleton;
import org.junit.Before;
import org.junit.Test;
import org.osgi.service.blueprint.reflect.ComponentMetadata;

public class AnnotationParserTest {

	private TxComponentMetaDataHelper helper;
	private ComponentDefinitionRegistry cdr;
	private Interceptor i;
	private AnnotationParser parser;
	
	@Before
	public void setup() {
        helper = Skeleton.newMock(TxComponentMetaDataHelper.class);
        Skeleton.getSkeleton(helper).setReturnValue(new MethodCall(TxComponentMetaDataHelper.class,
	    		"getComponentMethodTxAttribute", ComponentMetadata.class, String.class), null);
        cdr = Skeleton.newMock(ComponentDefinitionRegistry.class);
        i = Skeleton.newMock(Interceptor.class);
        parser = new AnnotationParser(cdr, i, helper);
	}
	
	@Test
	public void testFindAnnotation() {
		MutableBeanMetadata mbm = new BeanMetadataImpl();
		mbm.setId("testPojo");
		mbm.setClassName(AnnotatedPojo.class.getName());
	    parser.beforeInit(new AnnotatedPojo(), "testPojo", null, mbm);
	    
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"getComponentMethodTxAttribute", mbm, String.class), 3);
	    
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"setComponentTransactionData", cdr, mbm, "Required", "increment"), 1);
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"setComponentTransactionData", cdr, mbm, "Supports", "checkValue"), 1);
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"setComponentTransactionData", cdr, mbm, "Mandatory", "getRealObject"), 1);
	    
	    Skeleton.getSkeleton(cdr).assertCalledExactNumberOfTimes(new MethodCall(ComponentDefinitionRegistry.class,
	    		"registerInterceptorWithComponent", mbm, i), 1);
	}
	
	@Test
	public void testAnnotationsOverridenByXML() {
		
		MutableBeanMetadata mbm = new BeanMetadataImpl();
		mbm.setId("testPojo");
		mbm.setClassName(AnnotatedPojo.class.getName());
	    
		Skeleton.getSkeleton(helper).registerMethodCallHandler(new MethodCall(TxComponentMetaDataHelper.class,
	    		"getComponentMethodTxAttribute", ComponentMetadata.class, String.class), new MethodCallHandler() {
					
					public Object handle(MethodCall arg0, Skeleton arg1) throws Exception {
						if(arg0.getArguments()[1].equals("increment"))
							return "Never";
						
						return null;
					}
				});
		
		Skeleton.getSkeleton(cdr).setReturnValue(new MethodCall(ComponentDefinitionRegistry.class,
				"getInterceptors", mbm), Arrays.asList(i));
		
		parser.beforeInit(new AnnotatedPojo(), "testPojo", null, mbm);
	    
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"getComponentMethodTxAttribute", mbm, String.class), 3);
	    
	    Skeleton.getSkeleton(helper).assertNotCalled(new MethodCall(TxComponentMetaDataHelper.class,
	    		"setComponentTransactionData", cdr, mbm, "Required", "increment"));
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"setComponentTransactionData", cdr, mbm, "Supports", "checkValue"), 1);
	    Skeleton.getSkeleton(helper).assertCalledExactNumberOfTimes(new MethodCall(TxComponentMetaDataHelper.class,
	    		"setComponentTransactionData", cdr, mbm, "Mandatory", "getRealObject"), 1);
	    
	    Skeleton.getSkeleton(cdr).assertNotCalled(new MethodCall(ComponentDefinitionRegistry.class,
	    		"registerInterceptorWithComponent", mbm, i));
	}
	
	
	@Test(expected=IllegalArgumentException.class)
	public void testNoPrivateAnnotation() {
		MutableBeanMetadata mbm = new BeanMetadataImpl();
		mbm.setId("testPojo");
		mbm.setClassName(BadlyAnnotatedPojo1.class.getName());
	    parser.beforeInit(new BadlyAnnotatedPojo1(), "testPojo", null, mbm);
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testNoStaticAnnotation() {
		MutableBeanMetadata mbm = new BeanMetadataImpl();
		mbm.setId("testPojo");
		mbm.setClassName(BadlyAnnotatedPojo2.class.getName());
	    parser.beforeInit(new BadlyAnnotatedPojo2(), "testPojo", null, mbm);
	}
	
}
