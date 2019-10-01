import {
  IAction,
  IConversationStep,
  IInput,
  IOutput,
  IQuickReplies,
} from '../AxiosFunctions';

export const CONVERSATION_READY = 'READY';
export const CONVERSATION_ENDED = 'ENDED';
export const CONVERSATION_IN_PROGRESS = 'IN_PROGRESS';
export const CONVERSATION_ERROR = 'ERROR';
export const CONVERSATION_EXECUTION_INTERRUPTED = 'EXECUTION_INTERRUPTED';

export default class ConversationHelper {
  static getAction(conversationStep: IConversationStep[]) {
    let action = conversationStep.find(step => step.key.includes('actions'));
    if (!action) {
      return;
    }
    return (action as IAction).value;
  }

  static getInput(conversationStep: IConversationStep[]): string {
    const input = conversationStep.find(step => step.key.includes('input'));
    if (input) {
      return (input as IInput).value;
    }
  }

  static getOutput(conversationStep: IConversationStep[]): string[] {
    let i = conversationStep.findIndex(step => step.key.includes('output'));
    const output: string[] = [];
    if (i < 0) {
      return;
    }
    output.push((conversationStep[i] as IOutput).value);

    if (isNaN(parseInt(conversationStep[i].key.split(/[:]+/).pop(), 10))) {
      return output;
    } else {
      i++;
      while (parseInt(conversationStep[i].key.split(/[:]+/).pop(), 10)) {
        output.push((conversationStep[i] as IOutput).value);
        i++;
      }
      return output;
    }
  }

  static getQuickReplies(conversationStep: IConversationStep[]): string[] {
    const quickReplies = conversationStep.find(step =>
      step.key.includes('quickReplies'),
    );
    if (quickReplies) {
      return (quickReplies as IQuickReplies).value.map(
        quickReply => quickReply.value,
      );
    }
  }

  static getTimespan(conversationStep: IConversationStep[]): number {
    let firstConversationStepIndex = conversationStep.findIndex(
      step => !step.key.includes('properties'),
    );
    if (conversationStep.length - firstConversationStepIndex < 2) {
      return;
    }
    const timeSpan =
      conversationStep[conversationStep.length - 1].timestamp -
      conversationStep[firstConversationStepIndex].timestamp;
    return timeSpan;
  }

  static convertTimespan(timeSpan: number): string {
    if (!timeSpan) {
      return 'N/A';
    }
    return timeSpan > 999
      ? `${(timeSpan / 1000).toFixed(2)}s`
      : `${timeSpan}ms`;
  }
}
