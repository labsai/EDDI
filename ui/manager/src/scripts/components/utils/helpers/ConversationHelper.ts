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

export interface IConversationStepOutput {
  type: string;
  value: string;
}

export default class ConversationHelper {
  static getAction(conversationStep: IConversationStep[]) {
    let action = conversationStep.find(step => step.key.includes('actions'));
    if (!action) {
      return;
    }
    return (action as IAction).value.join(', ');
  }

  static getInput(conversationStep: IConversationStep[]): string {
    const input = conversationStep.find(step => step.key.includes('input'));
    if (input) {
      return (input as IInput).value;
    }
  }

  static parseConversationStepOutput(output: IOutput): IConversationStepOutput {
    let type: string;
    let value: string;
    if (typeof output.value === 'object') {
      type = output.value.type;
      if (type === 'image' || type === 'botIcon') {
        value = output.value.uri;
      } else {
        value = output.value.text;
      }
    } else {
      type = 'text';
      value = output.value;
    }
    return {
      type,
      value,
    };
  }

  static getOutput(
    conversationStep: IConversationStep[],
  ): IConversationStepOutput[] {
    let i = conversationStep.findIndex(step => step.key.includes('output'));
    const output: IConversationStepOutput[] = [];
    if (i < 0) {
      return;
    }
    output.push(
      this.parseConversationStepOutput(conversationStep[i] as IOutput),
    );

    if (isNaN(parseInt(conversationStep[i].key.split(/[:]+/).pop(), 10))) {
      return output;
    } else {
      i++;
      while (
        i < conversationStep.length &&
        parseInt(conversationStep[i].key.split(/[:]+/).pop(), 10)
      ) {
        output.push(
          this.parseConversationStepOutput(conversationStep[i] as IOutput),
        );
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
