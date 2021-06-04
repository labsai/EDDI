import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';

const styles: { [key: string]: IExtendedCSSProperties } = {
  truncate: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  textContainer: {
    display: 'flex',
    color: '#7A849E',
    fontSize: '13px',
    whiteSpace: 'pre-wrap',
  },
  textButton: {
    fontSize: '13px',
    color: '#16325C',
    whiteSpace: 'nowrap',
  },
};

interface IState {
  isExpanded: boolean;
}

interface IProps {
  style?: React.CSSProperties;
  text: string;
  length: number;
}

class TruncateTextComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      isExpanded: false,
    };
  }

  toggleText = () => {
    this.setState({ isExpanded: !this.state.isExpanded });
  };

  getTextStyling() {
    if (this.state.isExpanded) {
      return {};
    } else {
      return {
        ...styles.truncate,
        maxWidth: `${this.props.length}ch`,
      };
    }
  }
  render() {
    return (
      <div>
        {!!this.props.text && (
          <div>
            <div style={styles.textContainer}>
              <div style={this.getTextStyling()}>{this.props.text}</div>
              {!this.state.isExpanded &&
                this.props.text.length > this.props.length && (
                  <a style={styles.textButton} onClick={this.toggleText}>
                    {'See more'}
                  </a>
                )}
            </div>
            {this.state.isExpanded && (
              <div>
                <a style={styles.textButton} onClick={this.toggleText}>
                  {'See less'}
                </a>
              </div>
            )}
          </div>
        )}
      </div>
    );
  }
}

const ComposedTruncateTextComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('TruncateTextComponent'),
)(TruncateTextComponent);

export default ComposedTruncateTextComponent;
