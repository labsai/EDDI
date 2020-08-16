import {
  IAction,
  IConversationOutput,
  IConversationStep,
  IInput,
  IOutput,
  IOutputValue,
  IQuickReplies,
} from '../AxiosFunctions';
import * as _ from 'lodash';

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
    return (action as IAction).value.join(', ');
  }

  static getInput(conversationStep: IConversationStep[]): string {
    const input = conversationStep.find(step => step.key.includes('input'));
    if (input) {
      return (input as IInput).value;
    }
  }

  static parseConversationOutput(output: IOutputValue | string): IOutputValue {
    if (typeof output === 'string') {
      return {
        text: output,
        type: 'text',
      };
    }
    return output;
  }

  static getOutput(conversationOutput: IConversationOutput): IOutputValue[] {
    const output: IOutputValue[] = [];
    if (conversationOutput.output) {
      for (let o of conversationOutput.output) {
        output.push(this.parseConversationOutput(o));
      }
    }
    return output;
  }

  static getQuickReplies(converstionOutput: IConversationOutput): string[] {
    if (!_.isEmpty(converstionOutput.quickReplies)) {
      return converstionOutput.quickReplies.map(qr => qr.value);
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
