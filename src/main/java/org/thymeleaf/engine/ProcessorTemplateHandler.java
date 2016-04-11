/*
 * =============================================================================
 * 
 *   Copyright (c) 2011-2016, The THYMELEAF team (http://www.thymeleaf.org)
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 * =============================================================================
 */
package org.thymeleaf.engine;

import java.util.Arrays;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.context.IEngineContext;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.TemplateModelController.SkipBody;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.model.ICDATASection;
import org.thymeleaf.model.ICloseElementTag;
import org.thymeleaf.model.IComment;
import org.thymeleaf.model.IDocType;
import org.thymeleaf.model.IOpenElementTag;
import org.thymeleaf.model.IProcessingInstruction;
import org.thymeleaf.model.IStandaloneElementTag;
import org.thymeleaf.model.ITemplateEnd;
import org.thymeleaf.model.ITemplateEvent;
import org.thymeleaf.model.ITemplateStart;
import org.thymeleaf.model.IText;
import org.thymeleaf.model.IXMLDeclaration;
import org.thymeleaf.processor.cdatasection.ICDATASectionProcessor;
import org.thymeleaf.processor.comment.ICommentProcessor;
import org.thymeleaf.processor.doctype.IDocTypeProcessor;
import org.thymeleaf.processor.element.IElementModelProcessor;
import org.thymeleaf.processor.element.IElementProcessor;
import org.thymeleaf.processor.element.IElementTagProcessor;
import org.thymeleaf.processor.processinginstruction.IProcessingInstructionProcessor;
import org.thymeleaf.processor.templateboundaries.ITemplateBoundariesProcessor;
import org.thymeleaf.processor.text.ITextProcessor;
import org.thymeleaf.processor.xmldeclaration.IXMLDeclarationProcessor;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.util.Validate;

/**
 *
 * @author Daniel Fern&aacute;ndez
 * @since 3.0.0
 *
 */
public final class ProcessorTemplateHandler implements ITemplateHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProcessorTemplateHandler.class);


    private static final ITemplateBoundariesProcessor[] EMPTY_TEMPLATE_BOUNDARIES_PROCESSORS = new ITemplateBoundariesProcessor[0];
    private static final ICDATASectionProcessor[] EMPTY_CDATA_SECTION_PROCESSORS = new ICDATASectionProcessor[0];
    private static final ICommentProcessor[] EMPTY_COMMENT_PROCESSORS = new ICommentProcessor[0];
    private static final IDocTypeProcessor[] EMPTY_DOCTYPE_PROCESSORS = new IDocTypeProcessor[0];
    private static final IProcessingInstructionProcessor[] EMPTY_PROCESSING_INSTRUCTION_PROCESSORS = new IProcessingInstructionProcessor[0];
    private static final ITextProcessor[] EMPTY_TEXT_PROCESSORS = new ITextProcessor[0];
    private static final IXMLDeclarationProcessor[] EMPTY_XML_DECLARATION_PROCESSORS = new IXMLDeclarationProcessor[0];


    // Structure handlers are reusable objects that will be used by processors in order to instruct the engine to
    // do things with the processed structures themselves (things that cannot be directly done from the processors like
    // removing structures or iterating elements)
    private final ElementTagStructureHandler elementTagStructureHandler;
    private final ElementModelStructureHandler elementModelStructureHandler;
    private final TemplateBoundariesStructureHandler templateBoundariesStructureHandler;
    private final CDATASectionStructureHandler cdataSectionStructureHandler;
    private final CommentStructureHandler commentStructureHandler;
    private final DocTypeStructureHandler docTypeStructureHandler;
    private final ProcessingInstructionStructureHandler processingInstructionStructureHandler;
    private final TextStructureHandler textStructureHandler;
    private final XMLDeclarationStructureHandler xmlDeclarationStructureHandler;


    private ITemplateHandler next = null;

    private IEngineConfiguration configuration = null;
    private AttributeDefinitions attributeDefinitions = null;
    private TemplateMode templateMode = null;

    private ITemplateContext context = null;
    private IEngineContext engineContext = null;


    // These arrays will be initialized with all the registered processors for the different kind of non-element
    // processors. This is done so because non-element processors will not change during the execution of the engine
    // (whereas element processors can). And they are kept in the form of an array because they will be faster to
    // iterate than asking every time the configuration object for the Set of processors and creating an iterator for it
    private ITemplateBoundariesProcessor[] templateBoundariesProcessors = null;
    private ICDATASectionProcessor[] cdataSectionProcessors = null;
    private ICommentProcessor[] commentProcessors = null;
    private IDocTypeProcessor[] docTypeProcessors = null;
    private IProcessingInstructionProcessor[] processingInstructionProcessors = null;
    private ITextProcessor[] textProcessors = null;
    private IXMLDeclarationProcessor[] xmlDeclarationProcessors = null;


    private Integer initialContextLevel = null;


    // The modelController will be in charge of deciding if we have to skip the processing of an event, because it has to be
    // discarded or maybe because events are being gathered for future processing as a whole (e.g. iteration or
    // element model processors).
    private TemplateModelController modelController = null;


    private IGatheringModelProcessable currentGatheringModel = null;


    private boolean throttleEngine = false;
    private TemplateFlowController flowController = null;
    private IEngineProcessable[] pendingProcessings = null;
    private int pendingProcessingsSize = 0;
    private DecreaseContextLevelProcessable DECREASE_CONTEXT_LEVEL_PROCESSABLE = null; // This one can be cached



    /**
     * <p>
     *   Creates a new instance of this handler.
     * </p>
     *
     */
    public ProcessorTemplateHandler() {

        super();

        this.elementTagStructureHandler = new ElementTagStructureHandler();
        this.elementModelStructureHandler = new ElementModelStructureHandler();
        this.templateBoundariesStructureHandler = new TemplateBoundariesStructureHandler();
        this.cdataSectionStructureHandler = new CDATASectionStructureHandler();
        this.commentStructureHandler = new CommentStructureHandler();
        this.docTypeStructureHandler = new DocTypeStructureHandler();
        this.processingInstructionStructureHandler = new ProcessingInstructionStructureHandler();
        this.textStructureHandler = new TextStructureHandler();
        this.xmlDeclarationStructureHandler = new XMLDeclarationStructureHandler();

    }




    @Override
    public void setNext(final ITemplateHandler next) {
        this.next = next;
    }




    @Override
    public void setContext(final ITemplateContext context) {

        this.context = context;
        Validate.notNull(this.context, "Context cannot be null");
        Validate.notNull(this.context.getTemplateMode(), "Template Mode returned by context cannot be null");

        this.configuration = context.getConfiguration();
        Validate.notNull(this.configuration, "Engine Configuration returned by context cannot be null");
        Validate.notNull(this.configuration.getElementDefinitions(), "Element Definitions returned by the Engine Configuration cannot be null");
        Validate.notNull(this.configuration.getAttributeDefinitions(), "Attribute Definitions returned by the Engine Configuration cannot be null");

        this.attributeDefinitions = this.configuration.getAttributeDefinitions();

        this.templateMode = this.context.getTemplateMode(); // Just a way to avoid doing the call each time

        if (this.context instanceof IEngineContext) {
            this.engineContext = (IEngineContext) this.context;
        } else {
            logger.warn("Unknown implementation of the " + ITemplateContext.class.getName() + " interface: " +
                        this.context.getClass().getName() + ". Local variable support will be DISABLED (this " +
                        "includes iteration, target selection and inlining). In order to enable these, context " +
                        "implementations should also implement the " + IEngineContext.class.getName() +
                        " interface.");
            this.engineContext = null;
        }

        // Instance the gatherer
        this.modelController = new TemplateModelController(this.configuration, this.templateMode, this, this.engineContext);
        this.modelController.setTemplateFlowController(this.flowController); // Might have been already initialized or not
        this.DECREASE_CONTEXT_LEVEL_PROCESSABLE = new DecreaseContextLevelProcessable(this.engineContext, this.flowController);

        // Obtain all processor sets and compute sizes
        final Set<ITemplateBoundariesProcessor> templateBoundariesProcessorSet = this.configuration.getTemplateBoundariesProcessors(this.templateMode);
        final Set<ICDATASectionProcessor> cdataSectionProcessorSet = this.configuration.getCDATASectionProcessors(this.templateMode);
        final Set<ICommentProcessor> commentProcessorSet = this.configuration.getCommentProcessors(this.templateMode);
        final Set<IDocTypeProcessor> docTypeProcessorSet = this.configuration.getDocTypeProcessors(this.templateMode);
        final Set<IProcessingInstructionProcessor> processingInstructionProcessorSet = this.configuration.getProcessingInstructionProcessors(this.templateMode);
        final Set<ITextProcessor> textProcessorSet = this.configuration.getTextProcessors(this.templateMode);
        final Set<IXMLDeclarationProcessor> xmlDeclarationProcessorSet = this.configuration.getXMLDeclarationProcessors(this.templateMode);

        // Initialize arrays containing the processors for all the non-element structures (these do not change during execution)
        this.templateBoundariesProcessors =
                templateBoundariesProcessorSet.size() == 0? EMPTY_TEMPLATE_BOUNDARIES_PROCESSORS : templateBoundariesProcessorSet.toArray(new ITemplateBoundariesProcessor[templateBoundariesProcessorSet.size()]);
        this.cdataSectionProcessors =
                cdataSectionProcessorSet.size() == 0? EMPTY_CDATA_SECTION_PROCESSORS : cdataSectionProcessorSet.toArray(new ICDATASectionProcessor[cdataSectionProcessorSet.size()]);
        this.commentProcessors =
                commentProcessorSet.size() == 0? EMPTY_COMMENT_PROCESSORS : commentProcessorSet.toArray(new ICommentProcessor[commentProcessorSet.size()]);
        this.docTypeProcessors =
                docTypeProcessorSet.size() == 0? EMPTY_DOCTYPE_PROCESSORS : docTypeProcessorSet.toArray(new IDocTypeProcessor[docTypeProcessorSet.size()]);
        this.processingInstructionProcessors =
                processingInstructionProcessorSet.size() == 0? EMPTY_PROCESSING_INSTRUCTION_PROCESSORS : processingInstructionProcessorSet.toArray(new IProcessingInstructionProcessor[processingInstructionProcessorSet.size()]);
        this.textProcessors =
                textProcessorSet.size() == 0? EMPTY_TEXT_PROCESSORS : textProcessorSet.toArray(new ITextProcessor[textProcessorSet.size()]);
        this.xmlDeclarationProcessors =
                xmlDeclarationProcessorSet.size() == 0? EMPTY_XML_DECLARATION_PROCESSORS : xmlDeclarationProcessorSet.toArray(new IXMLDeclarationProcessor[xmlDeclarationProcessorSet.size()]);

    }




    public void setFlowController(final TemplateFlowController flowController) {
        this.flowController = flowController;
        this.throttleEngine = (this.flowController != null);
        if (this.throttleEngine && this.modelController != null) {
            this.modelController.setTemplateFlowController(this.flowController);
        }
        if (this.throttleEngine && this.engineContext != null) {
            this.DECREASE_CONTEXT_LEVEL_PROCESSABLE = new DecreaseContextLevelProcessable(this.engineContext, this.flowController);
        }
    }








    @Override
    public void handleTemplateStart(final ITemplateStart itemplateStart) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(itemplateStart);
            return;
        }


        /*
         * Save the initial engine context level, so that after processing we can ensure it matches
         */
        if (this.engineContext != null) {
            this.initialContextLevel = Integer.valueOf(this.engineContext.level());
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.templateBoundariesProcessors.length == 0) {
            this.next.handleTemplateStart(itemplateStart);
            return;
        }


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        Model model = null;
        ITemplateHandler modelHandler = this;
        final TemplateBoundariesStructureHandler structureHandler = this.templateBoundariesStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; i < this.templateBoundariesProcessors.length; i++) {

            structureHandler.reset();

            this.templateBoundariesProcessors[i].processTemplateStart(this.context, itemplateStart, structureHandler);

            if (this.engineContext != null) {
                structureHandler.applyContextModifications(this.engineContext);
            }

            if (structureHandler.insertText) {

                model = resetModel(model, true);
                model.add(new Text(structureHandler.insertTextValue));
                modelHandler = structureHandler.insertTextProcessable? this : this.next;

            } else if (structureHandler.insertModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.insertModelValue);
                modelHandler = structureHandler.insertModelProcessable? this : this.next;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        this.next.handleTemplateStart(itemplateStart);


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }








    @Override
    public void handleTemplateEnd(final ITemplateEnd itemplateEnd) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(itemplateEnd);
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.templateBoundariesProcessors.length == 0) {
            this.next.handleTemplateEnd(itemplateEnd);
            return;
        }


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        Model model = null;
        ITemplateHandler modelHandler = this;
        final TemplateBoundariesStructureHandler structureHandler = this.templateBoundariesStructureHandler;

        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; i < this.templateBoundariesProcessors.length; i++) {

            structureHandler.reset();

            this.templateBoundariesProcessors[i].processTemplateEnd(this.context, itemplateEnd, structureHandler);

            if (this.engineContext != null) {
                structureHandler.applyContextModifications(this.engineContext);
            }

            if (structureHandler.insertText) {

                model = resetModel(model, true);
                model.add(new Text(structureHandler.insertTextValue));
                modelHandler = structureHandler.insertTextProcessable? this : this.next;

            } else if (structureHandler.insertModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.insertModelValue);
                modelHandler = structureHandler.insertModelProcessable? this : this.next;

            }

        }


        /*
         * QUEUE MODEL HANDLING AND LATEST CHECKS (IF WE ARE THROTTLING)
         */
        if (this.throttleEngine && model != null && model.size() > 0) {
            queueProcessable(new TemplateEndModelProcessable(itemplateEnd, model, modelHandler, this, this.next, this.flowController));
            return;
        }


        /*
         * PROCESS THE QUEUE, launching all the queued events (BEFORE DELEGATING)
         */
        if (model != null) {
            model.process(modelHandler);
        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN.
         */
        this.next.handleTemplateEnd(itemplateEnd);


        /*
         * LAST ROUND OF CHECKS. If we have not returned our indexes to -1, something has gone wrong during processing
         */
        performTearDownChecks(itemplateEnd);

    }




    void performTearDownChecks(final ITemplateEnd itemplateEnd) {

        if (this.modelController.getModelLevel() != 0) {
            throw new TemplateProcessingException(
                    "Bad markup or template processing sequence. Model level is != 0 (" + this.modelController.getModelLevel() + ") " +
                            "at template end.", itemplateEnd.getTemplateName(), itemplateEnd.getLine(), itemplateEnd.getCol());
        }
        if (this.engineContext != null) {
            if (this.engineContext.level() != this.initialContextLevel.intValue()) {
                throw new TemplateProcessingException(
                        "Bad markup or template processing sequence. Context level after processing (" + this.engineContext.level() + ") " +
                                "does not correspond to context level before processing (" + this.initialContextLevel.intValue() + ").",
                        itemplateEnd.getTemplateName(), itemplateEnd.getLine(), itemplateEnd.getCol());
            }
        }

    }








    @Override
    public void handleText(final IText itext) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(itext);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessText(itext)) {
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.textProcessors.length == 0) {
            this.next.handleText(itext);
            return;
        }


        /*
         * CAST EVENT TO ENGINE-SPECIFIC IMPLEMENTATION
         */
        Text text = Text.asEngineText(itext);


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        boolean discardEvent = false;
        Model model = null;
        ITemplateHandler modelHandler = this;
        final TextStructureHandler structureHandler = this.textStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; !discardEvent && i < this.textProcessors.length; i++) {

            structureHandler.reset();

            this.textProcessors[i].process(this.context, text, structureHandler);

            if (structureHandler.setText) {

                text = new Text(structureHandler.setTextValue);

            } else if (structureHandler.replaceWithModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.replaceWithModelValue);
                modelHandler = structureHandler.replaceWithModelProcessable? this : this.next;
                discardEvent = true;

            } else if (structureHandler.removeText) {

                model = null;
                discardEvent = true;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!discardEvent) {
            this.next.handleText(text);
        }


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }








    @Override
    public void handleComment(final IComment icomment) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(icomment);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessComment(icomment)) {
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.commentProcessors.length == 0) {
            this.next.handleComment(icomment);
            return;
        }


        /*
         * CAST EVENT TO ENGINE-SPECIFIC IMPLEMENTATION
         */
        Comment comment = Comment.asEngineComment(icomment);


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        boolean discardEvent = false;
        Model model = null;
        ITemplateHandler modelHandler = this;
        final CommentStructureHandler structureHandler = this.commentStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; !discardEvent && i < this.commentProcessors.length; i++) {

            structureHandler.reset();

            this.commentProcessors[i].process(this.context, comment, structureHandler);

            if (structureHandler.setContent) {

                comment = new Comment(comment.prefix, structureHandler.setContentValue, comment.suffix);

            } else if (structureHandler.replaceWithModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.replaceWithModelValue);
                modelHandler = structureHandler.replaceWithModelProcessable? this : this.next;
                discardEvent = true;

            } else if (structureHandler.removeComment) {

                model = null;
                discardEvent = true;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!discardEvent) {
            this.next.handleComment(comment);
        }


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }







    
    @Override
    public void handleCDATASection(final ICDATASection icdataSection) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(icdataSection);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessCDATASection(icdataSection)) {
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.cdataSectionProcessors.length == 0) {
            this.next.handleCDATASection(icdataSection);
            return;
        }


        /*
         * CAST EVENT TO ENGINE-SPECIFIC IMPLEMENTATION
         */
        CDATASection cdataSection = CDATASection.asEngineCDATASection(icdataSection);


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        boolean discardEvent = false;
        Model model = null;
        ITemplateHandler modelHandler = this;
        final CDATASectionStructureHandler structureHandler = this.cdataSectionStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; !discardEvent && i < this.cdataSectionProcessors.length; i++) {

            structureHandler.reset();

            this.cdataSectionProcessors[i].process(this.context, cdataSection, structureHandler);

            if (structureHandler.setContent) {

                cdataSection = new CDATASection(cdataSection.prefix, structureHandler.setContentValue, cdataSection.suffix);

            } else if (structureHandler.replaceWithModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.replaceWithModelValue);
                modelHandler = structureHandler.replaceWithModelProcessable? this : this.next;
                discardEvent = true;

            } else if (structureHandler.removeCDATASection) {

                model = null;
                discardEvent = true;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!discardEvent) {
            this.next.handleCDATASection(cdataSection);
        }


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }








    @Override
    public void handleStandaloneElement(final IStandaloneElementTag istandaloneElementTag) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(istandaloneElementTag);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessStandaloneElement(istandaloneElementTag)) {
            return;
        }


        /*
         * CAST (WITHOUT CLONING) TO ENGINE-SPECIFIC IMPLEMENTATION, which will ease the handling of the structure during processing
         */
        StandaloneElementTag standaloneElementTag = StandaloneElementTag.asEngineStandaloneElementTag(istandaloneElementTag);


        /*
         * OBTAIN THE CURRENT SYNTHETIC MODEL (if any)
         */
        final IGatheringModelProcessable currentGatheringModel = obtainCurrentGatheringModel();


        /*
         * FAIL FAST in case this tag has no associated processors and we have no reason to pay attention to it
         * anyway (because of being suspended). This avoids cast to engine-specific implementation for most cases.
         */
        if (currentGatheringModel == null && !standaloneElementTag.hasAssociatedProcessors()) {

            if (this.engineContext != null) {
                this.engineContext.increaseLevel();
            }

            this.next.handleStandaloneElement(standaloneElementTag);

            if (!this.throttleEngine || !this.flowController.stopProcessing) {
                if (this.engineContext != null) {
                    this.engineContext.decreaseLevel();
                }
            } else {
                queueProcessable(this.DECREASE_CONTEXT_LEVEL_PROCESSABLE);
            }

            return;

        }


        /*
         * DECLARE THE STATE VARS NEEDED FOR PROCESSOR EXECUTION
         */
        final ProcessorExecutionVars vars =
                (currentGatheringModel == null? new ProcessorExecutionVars() : currentGatheringModel.initializeProcessorExecutionVars());


        /*
         * INCREASE THE CONTEXT LEVEL so that all local variables created during the execution of processors
         * are available for the rest of the processors as well as the body of the tag
         */
        if (this.engineContext != null) {
            this.engineContext.increaseLevel();
        }


        /*
         * GET THE STRUCTURE HANDLERS INTO LOCAL VARS
         */
        final ElementTagStructureHandler tagStructureHandler = this.elementTagStructureHandler;
        final ElementModelStructureHandler modelStructureHandler = this.elementModelStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        IElementProcessor processor;
        while (!vars.discardEvent && (processor = vars.processorIterator.next(standaloneElementTag)) != null) {

            tagStructureHandler.reset();
            modelStructureHandler.reset();

            if (processor instanceof IElementTagProcessor) {

                final IElementTagProcessor elementProcessor = ((IElementTagProcessor)processor);
                elementProcessor.process(this.context, standaloneElementTag, tagStructureHandler);

                tagStructureHandler.applyContextModifications(this.engineContext);
                standaloneElementTag =
                        tagStructureHandler.applyAttributes(this.attributeDefinitions, standaloneElementTag);

                if (tagStructureHandler.iterateElement) {

                    // Initialize a gathering model
                    this.modelController.startGatheringIteratedModel(
                            standaloneElementTag, vars,
                            tagStructureHandler.iterVariableName,
                            tagStructureHandler.iterStatusVariableName,
                            tagStructureHandler.iteratedObject);

                    // Note we DO NOT DECREASE THE EXEC LEVEL -- we need processIteration() to read our data
                    // Note we DO NOT DECREASE THE CONTEXT LEVEL -- we need the variables stored there, if any

                    // Process the gathering model
                    final IGatheringModelProcessable gatheredModel = this.modelController.getGatheredModel();
                    this.modelController.resetGathering();

                    if (!this.throttleEngine) {
                        gatheredModel.process();
                    } else {
                        queueProcessable(gatheredModel);
                    }

                    // Complete exit of the handler method: no more processing to do from here
                    return;

                } else if (tagStructureHandler.setBodyText) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    // Prepare the text node that will be added to the queue (which will be suspended)
                    final Text text = new Text(tagStructureHandler.setBodyTextValue);
                    vars.modelAfter.add(text);
                    vars.modelAfterProcessable = tagStructureHandler.setBodyTextProcessable;

                    // Initialize the iterated model object
                    final GatheringModelProcessable equivalentSyntheticModel =
                            this.modelController.createStandaloneEquivalentModel(standaloneElementTag, vars);

                    // Note we DO NOT DECREASE THE EXEC LEVEL -- that will be the responsibility of handleOpenElement
                    // Note we DO NOT DECREASE THE CONTEXT LEVEL -- we need the variables stored there, if any

                    // Fire the now-equivalent events. Note the handleOpenElement event will take care of the suspended queue
                    if (!this.throttleEngine) {
                        equivalentSyntheticModel.process();
                    } else {
                        queueProcessable(equivalentSyntheticModel);
                    }

                    // Complete exit of the handler method: no more processing to do from here
                    return;

                } else if (tagStructureHandler.setBodyModel) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    // Prepare the queue (that we will suspend)
                    // Model will be automatically cloned if mutable
                    vars.modelAfter.addModel(tagStructureHandler.setBodyModelValue);
                    vars.modelAfterProcessable = tagStructureHandler.setBodyModelProcessable;

                    // Initialize the iterated model object
                    final GatheringModelProcessable equivalentSyntheticModel =
                            this.modelController.createStandaloneEquivalentModel(standaloneElementTag, vars);

                    // Note we DO NOT DECREASE THE EXEC LEVEL -- that will be the responsibility of handleOpenElement
                    // Note we DO NOT DECREASE THE CONTEXT LEVEL -- we need the variables stored there, if any

                    // Fire the now-equivalent events. Note the handleOpenElement event will take care of the suspended queue
                    if (!this.throttleEngine) {
                        equivalentSyntheticModel.process();
                    } else {
                        queueProcessable(equivalentSyntheticModel);
                    }

                    // Complete exit of the handler method: no more processing to do from here
                    return;

                } else if (tagStructureHandler.insertBeforeModel) {

                    // Initialize model
                    vars.modelBefore = resetModel(vars.modelBefore, true);

                    vars.modelBefore.addModel(tagStructureHandler.insertBeforeModelValue);

                } else if (tagStructureHandler.insertImmediatelyAfterModel) {

                    // We will only be resetting the queue if we had set it to be executed before delegating, as in that
                    // case adding our new model to the beginning of what already is in the queue would make no sense
                    // Initialize model
                    if (vars.modelAfter == null) {
                        vars.modelAfter = resetModel(vars.modelAfter, true);
                    }

                    // No cleaning the queue, as we are not setting the entire body, so we will respect whatever
                    // was already added to the body queue, simply adding our insertion at the beginning of it all
                    vars.modelAfterProcessable = tagStructureHandler.insertImmediatelyAfterModelProcessable;

                    // Model will be automatically cloned if mutable
                    vars.modelAfter.insertModel(0, tagStructureHandler.insertImmediatelyAfterModelValue);

                    // No intervention on the body flags - we will not be removing the body, just inserting before it

                } else if (tagStructureHandler.replaceWithText) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    vars.modelAfterProcessable = tagStructureHandler.replaceWithTextProcessable;

                    // No need to clone the text buffer because, as we are removing the tag, we will execute the queue
                    // (containing only the text node) immediately. No further processors are to be executed
                    vars.modelAfter.add(new Text(tagStructureHandler.replaceWithTextValue));

                    vars.discardEvent = true;

                } else if (tagStructureHandler.replaceWithModel) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    vars.modelAfterProcessable = tagStructureHandler.replaceWithModelProcessable;

                    // Model will be automatically cloned if mutable
                    vars.modelAfter.addModel(tagStructureHandler.replaceWithModelValue);

                    vars.discardEvent = true;

                } else if (tagStructureHandler.removeElement) {

                    // Initialize model (if it already exists)
                    vars.modelAfter = resetModel(vars.modelAfter, false);

                    vars.discardEvent = true;

                } else if (tagStructureHandler.removeTags) {

                    // No modifications to the queue - it's just the tag that will be removed, not its possible contents

                    vars.discardEvent = true;

                }
                // No way to process 'removeBody' or 'removeAllButFirstChild' on a standalone tag

            } else if (processor instanceof IElementModelProcessor) {

                /*
                 * This is an Element Model processor, which means that before executing we might need to gather
                 * all the model that is inside the element (including the element's events themselves) and then,
                 * once all model has been gathered, call the processor. Note this process is quite similar to
                 * that of iteration.
                 *
                 * In order to know whether we need to start the model gathering process, or if just finished it
                 * and we need to actually execute the processor, we will ask the elementProcessorIterator to know
                 * if this is the first or the second time we execute this processor.
                 */

                if (!vars.processorIterator.lastWasRepeated()){

                    if ((vars.modelBefore != null && vars.modelBefore.size() > 0) || (vars.modelAfter != null && vars.modelAfter.size() > 0)) {
                        throw new TemplateProcessingException(
                                "Cannot execute model processor " + processor.getClass().getName() + " as the body " +
                                "of the target element has already been modified by a previously executed processor " +
                                "on the same tag. Model processors cannot execute on already-modified bodies as these " +
                                "might contain unprocessable events (e.g. as a result of a 'th:text' or similar)",
                                standaloneElementTag.getTemplateName(), standaloneElementTag.getLine(), standaloneElementTag.getCol());
                    }

                    // Set the processor to be executed again, because this time we will just set the "model gathering" mechanism
                    vars.processorIterator.setLastToBeRepeated(standaloneElementTag);

                    // Initialize the gathering model
                    this.modelController.startGatheringDelayedModel(standaloneElementTag, vars);
                    final IGatheringModelProcessable newModel = this.modelController.getGatheredModel();
                    this.modelController.resetGathering();

                    // Note we DO NOT DECREASE THE MODEL LEVEL -- that will be done when we re-execute this after gathering model
                    // Note we DO NOT DECREASE THE EXEC LEVEL -- that will be done when we re-execute this after gathering model
                    // Note we DO NOT DECREASE THE CONTEXT LEVEL -- that's the responsibility of the close event

                    // Process the new gathering model (no need to wait for a "close" event, as this is a standalone)
                    if (!this.throttleEngine) {
                        newModel.process();
                    } else {
                        queueProcessable(newModel);
                    }

                    // Nothing else to be done by this handler... let's just queue the rest of the events in this element
                    return;

                }

                /*
                 * This is not the first time we try to execute this processor, which means the model gathering
                 * process has already taken place.
                 */

                // We will use the model buffer in order to save in number of Model objects created. This is safe
                // because we will only be calling one of these processors at a time, and the model contents will
                // be cloned after execution in order to insert them into the queue.
                //
                // NOTE we are not cloning the events themselves here. There should be no need, as we are going to
                //      re-locate these events into a new queue, and their old position (which will be executed
                //      anyway) will be ignored.
                final Model processedModel = new Model(currentGatheringModel.getInnerModel());

                ((IElementModelProcessor) processor).process(this.context, processedModel, modelStructureHandler);


                modelStructureHandler.applyContextModifications(this.engineContext);


                // Reset the skipbody flags so that this model can be executed in the same conditions as the original
                currentGatheringModel.resetGatheredSkipFlags();

                // Initialize model
                vars.modelAfter = resetModel(vars.modelAfter, true);

                vars.modelAfterProcessable = true; // We actually NEED TO process this queue

                // Set the model to be executed
                vars.modelAfter.addModel(processedModel);

                vars.discardEvent = true;

            } else {
                throw new IllegalStateException(
                        "An element has been found with an associated processor of type " + processor.getClass().getName() +
                        " which is neither a Tag Element Processor nor a Model Element Processor.");
            }

        }


        /*
         * QUEUE MODEL HANDLING (IF WE ARE THROTTLING)
         */
        if (this.throttleEngine &&
                ((vars.modelAfter != null && vars.modelAfter.size() > 0) || (vars.modelBefore != null && vars.modelBefore.size() > 0))) {
            queueProcessable(new StandaloneElementTagModelProcessable(standaloneElementTag, vars, this.engineContext, this.modelController, this.flowController, this, this.next));
            return;
        }


        /*
         * PROCESS THE QUEUE BEFORE DELEGATING, if specified to do so
         */
        if (vars.modelBefore != null) {
            vars.modelBefore.process(this.next); // This is never processable
        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!vars.discardEvent) {
            this.next.handleStandaloneElement(standaloneElementTag);
        }


        /*
         * PROCESS THE QUEUE, launching all the queued events
         */
        if (vars.modelAfter != null) {
            vars.modelAfter.process(vars.modelAfterProcessable ? this : this.next);
        }


        /*
         * DECREASE THE CONTEXT LEVEL once we have executed all the processors (and maybe a body if we added
         * one to the tag converting it into an open tag)
         */
        if (!this.throttleEngine || !this.flowController.stopProcessing) {
            if (this.engineContext != null) {
                this.engineContext.decreaseLevel();
            }
        } else {
            queueProcessable(this.DECREASE_CONTEXT_LEVEL_PROCESSABLE);
        }

    }








    @Override
    public void handleOpenElement(final IOpenElementTag iopenElementTag) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(iopenElementTag);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessOpenElement(iopenElementTag)) {
            return;
        }


        /*
         * CAST (WITHOUT CLONING) TO ENGINE-SPECIFIC IMPLEMENTATION, which will ease the handling of the structure during processing
         */
        OpenElementTag openElementTag = OpenElementTag.asEngineOpenElementTag(iopenElementTag);


        /*
         * OBTAIN THE CURRENT SYNTHETIC MODEL
         */
        final IGatheringModelProcessable currentGatheringModel = obtainCurrentGatheringModel();


        /*
         * FAIL FAST in case this tag has no associated processors and we have no reason to pay attention to it
         * anyway (because of being suspended). This avoids cast to engine-specific implementation for most cases.
         */
        if (currentGatheringModel == null && !openElementTag.hasAssociatedProcessors()) {
            this.next.handleOpenElement(openElementTag);
            return;
        }


        /*
         * DECLARE THE STATE VARS NEEDED FOR PROCESSOR EXECUTION
         */
        final ProcessorExecutionVars vars =
                (currentGatheringModel == null? new ProcessorExecutionVars() : currentGatheringModel.initializeProcessorExecutionVars());


        /*
         * GET THE STRUCTURE HANDLERS INTO LOCAL VARS
         */
        final ElementTagStructureHandler tagStructureHandler = this.elementTagStructureHandler;
        final ElementModelStructureHandler modelStructureHandler = this.elementModelStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        IElementProcessor processor;
        while (!vars.discardEvent && (processor = vars.processorIterator.next(openElementTag)) != null) {

            tagStructureHandler.reset();
            modelStructureHandler.reset();

            if (processor instanceof IElementTagProcessor) {

                final IElementTagProcessor elementProcessor = ((IElementTagProcessor)processor);
                elementProcessor.process(this.context, openElementTag, tagStructureHandler);

                tagStructureHandler.applyContextModifications(this.engineContext);
                openElementTag =
                        tagStructureHandler.applyAttributes(this.attributeDefinitions, openElementTag);

                if (tagStructureHandler.iterateElement) {

                    // Initialize the gathering model
                    this.modelController.startGatheringIteratedModel(
                            openElementTag, vars,
                            tagStructureHandler.iterVariableName,
                            tagStructureHandler.iterStatusVariableName,
                            tagStructureHandler.iteratedObject);

                    // Note we DO NOT DECREASE THE EXEC LEVEL -- we need processIteration() to read our data
                    // Note we DO NOT DECREASE THE CONTEXT LEVEL -- we need the variables stored there, if any

                    // Nothing else to be done by this handler... let's just queue the rest of the events to be iterated
                    return;

                } else if (tagStructureHandler.setBodyText) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    vars.modelAfterProcessable = tagStructureHandler.setBodyTextProcessable;

                    // Add the new Text to the queue
                    vars.modelAfter.add(new Text(tagStructureHandler.setBodyTextValue));

                    vars.skipBody = SkipBody.SKIP_ALL;

                } else if (tagStructureHandler.setBodyModel) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    vars.modelAfterProcessable = tagStructureHandler.setBodyModelProcessable;

                    // Model will be automatically cloned if mutable
                    vars.modelAfter.addModel(tagStructureHandler.setBodyModelValue);

                    vars.skipBody = SkipBody.SKIP_ALL;

                } else if (tagStructureHandler.insertBeforeModel) {

                    // Initialize model
                    vars.modelBefore = resetModel(vars.modelBefore, true);

                    vars.modelBefore.addModel(tagStructureHandler.insertBeforeModelValue);

                } else if (tagStructureHandler.insertImmediatelyAfterModel) {

                    // We will only be resetting the queue if we had set it to be executed before delegating, as in that
                    // case adding our new model to the beginning of what already is in the queue would make no sense
                    // Initialize model
                    if (vars.modelAfter == null) {
                        vars.modelAfter = resetModel(vars.modelAfter, true);
                    }

                    // No cleaning the queue, as we are not setting the entire body, so we will respect whatever
                    // was already added to the body queue, simply adding our insertion at the beginning of it all
                    vars.modelAfterProcessable = tagStructureHandler.insertImmediatelyAfterModelProcessable;

                    // Model will be automatically cloned if mutable
                    vars.modelAfter.insertModel(0, tagStructureHandler.insertImmediatelyAfterModelValue);

                    // No intervention on the body flags - we will not be removing the body, just inserting before it

                } else if (tagStructureHandler.replaceWithText) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    vars.modelAfterProcessable = tagStructureHandler.replaceWithTextProcessable;

                    // No need to clone the text buffer because, as we are removing the tag, we will execute the queue
                    // (containing only the text node) immediately. No further processors are to be executed
                    vars.modelAfter.add(new Text(tagStructureHandler.replaceWithTextValue));

                    vars.discardEvent = true;
                    vars.skipBody = SkipBody.SKIP_ALL;
                    vars.skipCloseTag = true;

                } else if (tagStructureHandler.replaceWithModel) {

                    // Initialize model
                    vars.modelAfter = resetModel(vars.modelAfter, true);

                    vars.modelAfterProcessable = tagStructureHandler.replaceWithModelProcessable;

                    // Model will be automatically cloned if mutable
                    vars.modelAfter.addModel(tagStructureHandler.replaceWithModelValue);

                    vars.discardEvent = true;
                    vars.skipBody = SkipBody.SKIP_ALL;
                    vars.skipCloseTag = true;

                } else if (tagStructureHandler.removeElement) {

                    // Initialize model (if it already exists)
                    vars.modelAfter = resetModel(vars.modelAfter, false);

                    vars.discardEvent = true;
                    vars.skipBody = SkipBody.SKIP_ALL;
                    vars.skipCloseTag = true;

                } else if (tagStructureHandler.removeTags) {

                    // No modifications to the queue - it's just the tag that will be removed, not its possible contents

                    vars.discardEvent = true;
                    vars.skipCloseTag = true;

                } else if (tagStructureHandler.removeBody) {

                    // Initialize model (if it already exists)
                    vars.modelAfter = resetModel(vars.modelAfter, false);

                    vars.skipBody = SkipBody.SKIP_ALL;

                } else if (tagStructureHandler.removeAllButFirstChild) {

                    // Initialize model (if it already exists)
                    vars.modelAfter = resetModel(vars.modelAfter, false);

                    vars.skipBody = SkipBody.PROCESS_ONE_ELEMENT;

                }

            } else if (processor instanceof IElementModelProcessor) {

                /*
                 * This is an Element Model processor, which means that before executing we might need to gather
                 * all the model that is inside the element (including the element's events themselves) and then,
                 * once all model has been gathered, call the processor. Note this process is quite similar to
                 * that of iteration.
                 *
                 * In order to know whether we need to start the model gathering process, or if just finished it
                 * and we need to actually execute the processor, we will ask the elementProcessorIterator to know
                 * if this is the first or the second time we execute this processor.
                 */

                if (!vars.processorIterator.lastWasRepeated()){

                    if ((vars.modelBefore != null && vars.modelBefore.size() > 0) || (vars.modelAfter != null && vars.modelAfter.size() > 0)) {
                        throw new TemplateProcessingException(
                                "Cannot execute model processor " + processor.getClass().getName() + " as the body " +
                                "of the target element has already been modified by a previously executed processor " +
                                "on the same tag. Model processors cannot execute on already-modified bodies as these " +
                                "might contain unprocessable events (e.g. as a result of a 'th:text' or similar)",
                                openElementTag.getTemplateName(), openElementTag.getLine(), openElementTag.getCol());
                    }

                    // Set the processor to be executed again, because this time we will just set the "model gathering" mechanism
                    vars.processorIterator.setLastToBeRepeated(openElementTag);

                    // Initialize the gathering model
                    this.modelController.startGatheringDelayedModel(openElementTag, vars);

                    // Note we DO NOT DECREASE THE MODEL LEVEL -- that will be done when we re-execute this after gathering model
                    // Note we DO NOT DECREASE THE EXEC LEVEL -- that will be done when we re-execute this after gathering model
                    // Note we DO NOT DECREASE THE CONTEXT LEVEL -- that's the responsibility of the close event

                    // Nothing else to be done by this handler... let's just queue the rest of the events in this element
                    return;

                }

                /*
                 * This is not the first time we try to execute this processor, which means the model gathering
                 * process has already taken place.
                 */

                // We will use the model buffer in order to save in number of Model objects created. This is safe
                // because we will only be calling one of these processors at a time, and the model contents will
                // be cloned after execution in order to insert them into the queue.
                //
                // NOTE we are not cloning the events themselves here. There should be no need, as we are going to
                //      re-locate these events into a new queue, and their old position (which will be executed
                //      anyway) will be ignored.
                final Model processedModel = new Model(currentGatheringModel.getInnerModel());

                ((IElementModelProcessor) processor).process(this.context, processedModel, modelStructureHandler);

                modelStructureHandler.applyContextModifications(this.engineContext);


                /*
                 * Now we will do the exact equivalent to what is performed for an Element Tag processor, when this
                 * returns a result of type "replaceWithModel".
                 */

                // Reset the skipbody flags so that this model can be executed in the same conditions as the original
                currentGatheringModel.resetGatheredSkipFlags();

                // Initialize model
                vars.modelAfter = resetModel(vars.modelAfter, true);

                vars.modelAfterProcessable = true; // We actually NEED TO process this queue

                vars.modelAfter.addModel(processedModel);

                vars.discardEvent = true;
                vars.skipBody = SkipBody.SKIP_ALL;
                vars.skipCloseTag = true;

            } else {
                throw new IllegalStateException(
                        "An element has been found with an associated processor of type " + processor.getClass().getName() +
                        " which is neither a Tag Element Processor nor a Model Element Processor.");
            }

        }


        /*
         * QUEUE MODEL HANDLING (IF WE ARE THROTTLING)
         */
        if (this.throttleEngine &&
                ((vars.modelAfter != null && vars.modelAfter.size() > 0) || (vars.modelBefore != null && vars.modelBefore.size() > 0))) {
            queueProcessable(new OpenElementTagModelProcessable(openElementTag, vars, this.modelController, this.flowController, this, this.next));
            return;
        }


        /*
         * PROCESS THE QUEUE BEFORE DELEGATING, if specified to do so
         */
        if (vars.modelBefore != null) {
            vars.modelBefore.process(this.next); // This is never processable
        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN and INCREASE THE MODEL LEVEL RIGHT AFTERWARDS
         */
        if (!vars.discardEvent) {
            this.next.handleOpenElement(openElementTag);
        }


        /*
         * PROCESS THE QUEUE, launching all the queued events. Note executing the queue after increasing the model
         * level makes sense even if what the queue contains is a replacement for the complete element (including open
         * and close tags), because that way whatever comes in the queue will be encapsulated in a different model level
         * and its internal open/close tags should not affect the correct delimitation of this block.
         */
        if (vars.modelAfter != null) {
            vars.modelAfter.process(vars.modelAfterProcessable ? this : this.next);
        }


        /*
         * SET BODY TO BE SKIPPED, if required. Importantly, this has to be done AFTER executing the queue
         */
        this.modelController.skip(vars.skipBody, vars.skipCloseTag);

    }








    @Override
    public void handleCloseElement(final ICloseElementTag icloseElementTag) {

        /*
         * FIRST OF ALL CHECK IF IT IS AN UNMATCHED CLOSE EVENT, and in such case process in a much simpler way
         */
        if (icloseElementTag.isUnmatched()) {
            handleUnmatchedCloseElement(icloseElementTag);
            return;
        }


        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(icloseElementTag);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessCloseElement(icloseElementTag)) {

            /*
             * IF WE JUST ENDED GATHERING A SYNTHETIC MODEL, PROCESS IT
             */
            if (this.modelController.isGatheringFinished()) {
                final IGatheringModelProcessable gatheredModel = this.modelController.getGatheredModel();
                this.modelController.resetGathering();
                if (!this.throttleEngine) {
                    gatheredModel.process();
                } else {
                    queueProcessable(gatheredModel);
                }
            }

            return;

        }


        /*
         * CALL THE NEXT HANDLER in the chain
         */
        this.next.handleCloseElement(icloseElementTag);

    }








    private void handleUnmatchedCloseElement(final ICloseElementTag icloseElementTag) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(icloseElementTag);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessUnmatchedCloseElement(icloseElementTag)) {
            return;
        }

        /*
         * -------------------------------------------------------------------------------------------------
         * THERE IS NOTHING ELSE THAT SHOULD BE DONE WITH AN UNMATCHED CLOSE ELEMENT. No processors apply...
         * -------------------------------------------------------------------------------------------------
         */

        /*
         * CALL THE NEXT HANDLER in the chain
         */
        this.next.handleCloseElement(icloseElementTag);

    }








    @Override
    public void handleDocType(final IDocType idocType) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(idocType);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessDocType(idocType)) {
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.docTypeProcessors.length == 0) {
            this.next.handleDocType(idocType);
            return;
        }


        /*
         * CAST EVENT TO ENGINE-SPECIFIC IMPLEMENTATION
         */
        DocType docType = DocType.asEngineDocType(idocType);


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        boolean discardEvent = false;
        Model model = null;
        ITemplateHandler modelHandler = this;
        final DocTypeStructureHandler structureHandler = this.docTypeStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; !discardEvent && i < this.docTypeProcessors.length; i++) {

            structureHandler.reset();

            this.docTypeProcessors[i].process(this.context, docType, structureHandler);

            if (structureHandler.setDocType) {

                docType =
                        new DocType(
                            structureHandler.setDocTypeKeyword,
                            structureHandler.setDocTypeElementName,
                            structureHandler.setDocTypePublicId,
                            structureHandler.setDocTypeSystemId,
                            structureHandler.setDocTypeInternalSubset);

            } else if (structureHandler.replaceWithModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.replaceWithModelValue);
                modelHandler = structureHandler.replaceWithModelProcessable? this : this.next;
                discardEvent = true;

            } else if (structureHandler.removeDocType) {

                model = null;
                discardEvent = true;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!discardEvent) {
            this.next.handleDocType(docType);
        }


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }





    
    
    
    @Override
    public void handleXMLDeclaration(final IXMLDeclaration ixmlDeclaration) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(ixmlDeclaration);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessXMLDeclaration(ixmlDeclaration)) {
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.xmlDeclarationProcessors.length == 0) {
            this.next.handleXMLDeclaration(ixmlDeclaration);
            return;
        }


        /*
         * CAST EVENT TO ENGINE-SPECIFIC IMPLEMENTATION
         */
        XMLDeclaration xmlDeclaration = XMLDeclaration.asEngineXMLDeclaration(ixmlDeclaration);


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        boolean discardEvent = false;
        Model model = null;
        ITemplateHandler modelHandler = this;
        final XMLDeclarationStructureHandler structureHandler = this.xmlDeclarationStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; !discardEvent && i < this.xmlDeclarationProcessors.length; i++) {

            structureHandler.reset();

            this.xmlDeclarationProcessors[i].process(this.context, xmlDeclaration, structureHandler);

            if (structureHandler.setXMLDeclaration) {

                xmlDeclaration =
                        new XMLDeclaration(
                                structureHandler.setXMLDeclarationKeyword,
                                structureHandler.setXMLDeclarationVersion,
                                structureHandler.setXMLDeclarationEncoding,
                                structureHandler.setXMLDeclarationStandalone);

            } else if (structureHandler.replaceWithModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.replaceWithModelValue);
                modelHandler = structureHandler.replaceWithModelProcessable? this : this.next;
                discardEvent = true;

            } else if (structureHandler.removeXMLDeclaration) {

                model = null;
                discardEvent = true;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!discardEvent) {
            this.next.handleXMLDeclaration(xmlDeclaration);
        }


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }








    @Override
    public void handleProcessingInstruction(final IProcessingInstruction iprocessingInstruction) {

        /*
         * If processing is stopped, we should queue this for later handling
         */
        if (this.throttleEngine && this.flowController.stopProcessing) {
            queueEvent(iprocessingInstruction);
            return;
        }


        /*
         * CHECK WHETHER WE ARE GATHERING AN ELEMENT's MODEL
         */
        if (!this.modelController.shouldProcessProcessingInstruction(iprocessingInstruction)) {
            return;
        }


        /*
         * FAIL FAST in case this structure has no associated processors.
         */
        if (this.processingInstructionProcessors.length == 0) {
            this.next.handleProcessingInstruction(iprocessingInstruction);
            return;
        }


        /*
         * CAST EVENT TO ENGINE-SPECIFIC IMPLEMENTATION
         */
        ProcessingInstruction processingInstruction = ProcessingInstruction.asEngineProcessingInstruction(iprocessingInstruction);


        /*
         * DECLARE VARIABLES THAT MIGHT BE NEEDED FOR TAKING ACTIONS INSTRUCTED BY THE PROCESSORS
         */
        boolean discardEvent = false;
        Model model = null;
        ITemplateHandler modelHandler = this;
        final ProcessingInstructionStructureHandler structureHandler = this.processingInstructionStructureHandler;


        /*
         * EXECUTE PROCESSORS
         */
        for (int i = 0; !discardEvent && i < this.processingInstructionProcessors.length; i++) {

            structureHandler.reset();

            this.processingInstructionProcessors[i].process(this.context, processingInstruction, structureHandler);

            if (structureHandler.setProcessingInstruction) {

                processingInstruction =
                        new ProcessingInstruction(
                                structureHandler.setProcessingInstructionTarget,
                                structureHandler.setProcessingInstructionContent);

            } else if (structureHandler.replaceWithModel) {

                model = resetModel(model, true);
                model.addModel(structureHandler.replaceWithModelValue);
                modelHandler = structureHandler.replaceWithModelProcessable? this : this.next;
                discardEvent = true;

            } else if (structureHandler.removeProcessingInstruction) {

                model = null;
                discardEvent = true;

            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN
         */
        if (!discardEvent) {
            this.next.handleProcessingInstruction(processingInstruction);
        }


        /*
         * PROCESS THE QUEUED MODEL IF NEEDED (or handle it as pending if we are throttling the engine)
         */
        if (model == null || model.size() == 0) {
            return;
        }
        if (!this.throttleEngine) {
            model.process(modelHandler);
        } else {
            queueProcessable(new SimpleModelProcessable(model, modelHandler, this.flowController));
        }

    }








    public void handlePending() {

        if (this.throttleEngine) {

            final TemplateFlowController controller = this.flowController;

            if (controller.stopProcessing) {
                controller.processorTemplateHandlerPending = true;
                return;
            }

            while (this.pendingProcessingsSize > 0) {
                final boolean processed = this.pendingProcessings[this.pendingProcessingsSize - 1].process();
                if (!processed) {
                    controller.processorTemplateHandlerPending = true;
                    return;
                }
                this.pendingProcessingsSize--;
            }

            controller.processorTemplateHandlerPending = false;

        }

    }




    private void ensurePendingCapacity() {
        if (this.pendingProcessings == null) {
            this.pendingProcessings = new IEngineProcessable[5];
            this.pendingProcessingsSize = 0;
        }
        if (this.pendingProcessingsSize == this.pendingProcessings.length) {
            this.pendingProcessings = Arrays.copyOf(this.pendingProcessings, this.pendingProcessings.length + 5);
        }
    }


    private void queueProcessable(final IEngineProcessable processableModel) {

        ensurePendingCapacity();

        final TemplateFlowController controller = this.flowController;

        this.pendingProcessings[this.pendingProcessingsSize] = processableModel;
        this.pendingProcessingsSize++;

        if (controller.stopProcessing) {
            controller.processorTemplateHandlerPending = true;
            return;
        }

        final boolean processed = this.pendingProcessings[this.pendingProcessingsSize - 1].process();
        if (!processed) {
            controller.processorTemplateHandlerPending = true;
            return;
        }
        this.pendingProcessingsSize--;

        controller.processorTemplateHandlerPending = false;

    }


    /*
     * This method will be called for any event that arrives from a previous handler in the chain (or the parser, cache...)
     * when the execution has already been stopped and we (potentially) have some work pending. The idea is to queue
     * these events at the end of the pending queue (i.e. at level 0) so that they are processed normally once all
     * pending work has been processed too.
     *
     * Note events used here should always come from previous handlers and never from the execution of pending work
     * itself, given all pending-work structures (i.e. all implementations of IEngineProcessable) should check
     * the "stopProcessing" flag before executing each event, so they should never produce additional pending events
     * that would potentially (and erroneously) be queued at level 0.
     */
    private void queueEvent(final ITemplateEvent event) {

        final SimpleModelProcessable pendingProcessableModel;
        if (this.pendingProcessingsSize > 0) {
            final IEngineProcessable level0Pending = this.pendingProcessings[0];
            if (level0Pending instanceof SimpleModelProcessable && ((SimpleModelProcessable)level0Pending).getModelHandler() == this) {
                pendingProcessableModel = (SimpleModelProcessable)level0Pending;
            } else {
                final Model model = new Model(this.configuration, this.templateMode);
                pendingProcessableModel = new SimpleModelProcessable(model, this, this.flowController);
                ensurePendingCapacity();
                System.arraycopy(this.pendingProcessings, 0, this.pendingProcessings, 1, this.pendingProcessingsSize);
                this.pendingProcessings[0] = pendingProcessableModel;
                this.pendingProcessingsSize++;
            }
        } else {
            final Model model = new Model(this.configuration, this.templateMode);
            pendingProcessableModel = new SimpleModelProcessable(model, this, this.flowController);
            ensurePendingCapacity();
            this.pendingProcessings[0] = pendingProcessableModel;
            this.pendingProcessingsSize++;
        }
        pendingProcessableModel.getModel().add(event);
        this.flowController.processorTemplateHandlerPending = true;

    }








    private IGatheringModelProcessable obtainCurrentGatheringModel() {
        final IGatheringModelProcessable gatheringModel = this.currentGatheringModel;
        this.currentGatheringModel = null;
        return gatheringModel;
    }


    void setCurrentGatheringModel(final IGatheringModelProcessable gatheringModel) {
        this.currentGatheringModel = gatheringModel;
    }




    private Model resetModel(final Model model, final boolean createIfNull) {
        if (model == null) {
            if (createIfNull) {
                return new Model(this.configuration, this.templateMode);
            }
            return model;
        }
        model.reset();
        return model;
    }


}