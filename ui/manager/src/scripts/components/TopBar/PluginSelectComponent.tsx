import * as React from 'react';
import * as Radium from 'radium';
import * as renderIf from 'render-if';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Select from 'react-select';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import Parser from '../utils/Parser';
import { historyPush } from '../../history';
import { pageEnum } from './NavigationComponent';

const styles: CSSProperties = {
  selectContainer: {
    width: '170px',
  },
  control: {
    display: 'flex',
    height: '42px',
    backgroundColor: '#FFF',
    borderRadius: '0',
    border: '0',
    ':hover': {
      cursor: 'pointer',
      backgroundColor: '#F7F9FB',
    },
  },
  singleValue: {
    color: '#7A849E',
  },
};

const customStyles = {
  indicatorsContainer: (base, state) => ({
    position: 'relative',
    borderLeft: '6px solid transparent',
    borderRight: '6px solid transparent',
    borderTop: '6px solid #16325C',
    height: '0',
    marginRight: '10px',
    width: '0',
  }),
  input: (base, state) => ({
    ...base,
    maxWidth: '60px',
    overflow: 'hidden',
    ':active': {
      outline: '0',
    },
  }),
  valueContainer: (base, state) => ({
    ...base,
    fontSize: '14px',
    overflow: 'hidden',
    marginLeft: '1px',
  }),
  option: (base, state) => ({
    ...base,
    color: '#16325C',
    fontSize: '14px',
    overflow: 'hidden',
    textAlign: 'left',
    ':hover': {
      backgroundColor: '#F7F9FB',
    },
  }),
};

const pluginResourceOptions = [
  'Regular dictionaries',
  'Behavior rules',
  'Output sets',
  'HTTP calls',
];

interface IOption {
  value: number;
  label: string;
}
interface IState {
  options: IOption[];
  selectedOption: IOption;
}
interface IProps {
  page: pageEnum;
}

class PluginSelectComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedOption: null,
      options: [],
    };
  }

  componentDidMount() {
    this.setOption();
  }

  componentWillReceiveProps(nextProps) {
    this.setOption(nextProps);
  }

  isPluginPage(props = this.props): boolean {
    return [
      pageEnum.regularDictionaries,
      pageEnum.behaviorRules,
      pageEnum.outputSets,
      pageEnum.httpCalls,
    ].includes(props.page);
  }

  setOption(props = this.props) {
    if (this.isPluginPage(props)) {
      this.setState({
        selectedOption: {
          value: props.page,
          label: pluginResourceOptions[props.page],
        },
      });
    } else {
      this.setState({ selectedOption: { value: -1, label: 'Resources' } });
    }
  }

  handleSelect = (option: IOption) => {
    if (!_.isEmpty(option)) {
      this.setState({ selectedOption: option });
      switch (option.value) {
        case 0:
          historyPush('/extensions', ['type=regularDictionaries']);
          return;
        case 1:
          historyPush('/extensions', ['type=behaviorRules']);
          return;
        case 2:
          historyPush('/extensions', ['type=outputSets']);
          return;
        case 3:
          historyPush('/extensions', ['type=httpCalls']);
          return;
        default:
          return;
      }
    }
  };

  getStyles() {
    if (this.isPluginPage()) {
      return {
        ...customStyles,
        control: (base, state) => ({
          ...base,
          ...styles.control,
          borderBottom: '3px solid #4A90E2',
        }),
        singleValue: (base, state) => ({
          ...base,
          ...styles.singleValue,
          color: '#16325C',
        }),
      };
    } else {
      return {
        ...customStyles,
        control: (base, state) => ({
          ...base,
          ...styles.control,
          borderBottom: '3px solid #E0E5EE',
        }),
        singleValue: (base, state) => ({
          ...base,
          ...styles.singleValue,
        }),
      };
    }
  }

  render() {
    return (
      <div style={styles.selectContainer}>
        <Select
          styles={this.getStyles()}
          value={this.state.selectedOption}
          options={pluginResourceOptions.map((pluginType, i) => {
            return { value: i, label: pluginType };
          })}
          onChange={this.handleSelect}
          isSearchable={false}
        />
      </div>
    );
  }
}

const ComposedPluginSelectComponent: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('PluginSelectComponent'),
)(PluginSelectComponent);

export default ComposedPluginSelectComponent;
